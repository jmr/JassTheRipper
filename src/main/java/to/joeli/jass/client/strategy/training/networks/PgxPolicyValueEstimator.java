package to.joeli.jass.client.strategy.training.networks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tensorflow.Result;
import org.tensorflow.SavedModelBundle;
import org.tensorflow.SessionFunction;
import org.tensorflow.ndarray.StdArrays;
import org.tensorflow.types.TFloat32;
import to.joeli.jass.client.game.Game;
import to.joeli.jass.client.game.GameSession;
import to.joeli.jass.client.strategy.helpers.PgxFeatureEncoder;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.mode.Mode;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wraps the pgx {@code PolicyValueNet} exported as a TF2 SavedModel.
 *
 * <p>The model expects full-information features (all four hands must be known, i.e.
 * the game must be determinized). Use only at leaf nodes inside MCTS, or in
 * cheating-mode games where all cards are visible.
 *
 * <h3>Network inputs</h3>
 * <ul>
 *   <li>{@code cm}: {@code (1, 36, 12)} float32 — card matrix from {@link PgxFeatureEncoder}</li>
 *   <li>{@code hd}: {@code (1, 20)} float32 — header from {@link PgxFeatureEncoder}</li>
 * </ul>
 *
 * <h3>Network outputs</h3>
 * <ul>
 *   <li>{@code logits}: {@code (1, 43)} float32 — unmasked action logits
 *       (indices 0–35 are card plays in pgx order, 36–42 are trump/Schiebe declarations)</li>
 *   <li>{@code value}: {@code (1,)} float32 — score differential / 100.0
 *       (multiply by {@link #TARGET_SCALE} to get points in approximately [−157, 157])</li>
 * </ul>
 *
 * <p>Weights come from the pgx {@code PolicyValueNet} converted to SavedModel via:
 * <pre>
 *   # 1. Extract weights (in pgx venv):
 *   python pgx/scripts/extract_pv_weights.py [--weights pv_gen3_s128.msgpack] --out /tmp/w.npz
 *   # 2. Export SavedModel (in a Python 3.11 + tensorflow==2.13.x venv):
 *   python pgx/scripts/export_pv_savedmodel.py [--weights /tmp/w.npz] \
 *       --out src/main/resources/models/pgx_pv/export
 * </pre>
 */
public class PgxPolicyValueEstimator {

    /**
     * The network outputs {@code value ≈ differential / TARGET_SCALE}.
     * Multiply by this to recover the score differential in points.
     */
    public static final float TARGET_SCALE = 100.0f;

    /**
     * Default SavedModel path, resolved relative to the working directory.
     * Run the export script to populate this directory before loading.
     */
    public static final String DEFAULT_MODEL_PATH =
            "src/main/resources/models/pgx_pv/export";

    private SavedModelBundle bundle;
    private SessionFunction servingFn;

    public static final Logger logger = LoggerFactory.getLogger(PgxPolicyValueEstimator.class);

    // ── Model loading ─────────────────────────────────────────────────────────

    /**
     * Loads the TF2 SavedModel from the given directory.
     *
     * @param path path to the SavedModel directory (contains {@code saved_model.pb})
     */
    public void loadModel(String path) {
        bundle = SavedModelBundle.load(path, "serve");
        servingFn = bundle.function("serving_default");
        logger.info("Loaded pgx PolicyValueNet from {}", path);
    }

    /** Loads from {@link #DEFAULT_MODEL_PATH}. */
    public void loadModel() {
        loadModel(DEFAULT_MODEL_PATH);
    }

    /** Returns {@code true} after a successful {@link #loadModel} call. */
    public boolean isLoaded() {
        return servingFn != null;
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Runs one forward pass through the network.
     * The game must be fully determinized (all four {@code player.getCards()} populated).
     *
     * @param game a determinized game state
     * <p>Not synchronized: the underlying {@code org.tensorflow} {@link SessionFunction}
     * (and the {@link org.tensorflow.Session} it wraps) is thread-safe, so the MCTS
     * determinization threads call the single shared session concurrently rather than
     * serializing on a lock. One shared bundle avoids the thread-pool oversubscription
     * that per-thread bundles caused (~3× slowdown).
     *
     * @return raw forward-pass result (logits 0–42 and unscaled value)
     */
    private ForwardResult forward(Game game) {
        return forward(PgxFeatureEncoder.encode(game));
    }

    /**
     * Runs one forward pass through the network on already-encoded features.
     * Shared by the card-play path ({@link PgxFeatureEncoder#encode(Game)}) and the
     * trumpf-selection path ({@link PgxFeatureEncoder#encodeTrumpSelection}).
     */
    private ForwardResult forward(PgxFeatureEncoder.Features feat) {
        if (!isLoaded()) {
            throw new IllegalStateException(
                    "PgxPolicyValueEstimator: model not loaded. Call loadModel() first.");
        }

        // Wrap (36, 12) → (1, 36, 12) and (20,) → (1, 20) for batched inference
        float[][][] cmBatch = {feat.cardMatrix};
        float[][] hdBatch = {feat.header};

        try (TFloat32 cmTensor = TFloat32.tensorOf(StdArrays.ndCopyOf(cmBatch));
             TFloat32 hdTensor = TFloat32.tensorOf(StdArrays.ndCopyOf(hdBatch));
             Result results = servingFn.call(
                     Map.of("cm", cmTensor, "hd", hdTensor))) {

            TFloat32 logitsTensor = (TFloat32) results.get("logits")
                    .orElseThrow(() -> new IllegalStateException("No 'logits' output from model"));  // (1, 43)
            TFloat32 valueTensor  = (TFloat32) results.get("value")
                    .orElseThrow(() -> new IllegalStateException("No 'value' output from model"));   // (1,)

            // logits: (1, 43) → float[1][43] → float[43]
            float[][] logits2d = StdArrays.array2dCopyOf(logitsTensor);
            // value:  (1,)    → float[1] → float
            float[] value1d   = StdArrays.array1dCopyOf(valueTensor);
            return new ForwardResult(logits2d[0], value1d[0]);
            // Result.close() handles all contained tensors
        }
    }

    // ── Value head ────────────────────────────────────────────────────────────

    /**
     * Predicts the score differential ({@code my_team_score − opp_team_score}) for the
     * current player's team, in the range approximately [−157, 157].
     *
     * <p>This is the value head of the network. Use it as an MCTS leaf evaluator
     * (analogous to {@link ScoreEstimator#predictScore} but returning a signed differential
     * rather than a raw team score).
     *
     * @param game a fully determinized game
     * @return score differential ≈ my_team − opp_team, in [−157, 157]
     */
    public double predictValue(Game game) {
        return forward(game).value * TARGET_SCALE;
    }

    // ── Policy head ───────────────────────────────────────────────────────────

    /**
     * Returns a probability distribution over legal cards suitable for the PUCT prior.
     *
     * <p>Applies softmax to all 43 logits, then masks to the provided legal cards and
     * renormalizes. Card logits use pgx indices (0–35), distinct from
     * {@code Card.ordinal()} — the mapping goes through {@link PgxFeatureEncoder#pgxIndex}.
     *
     * <p>Falls back to a uniform distribution if all masked probabilities sum to zero.
     *
     * @param game       a fully determinized game
     * @param legalCards the set of cards that may legally be played
     * @return masked and renormalized probability distribution over {@code legalCards}
     */
    public Map<Card, Float> predictPriorOverLegal(Game game, Set<Card> legalCards) {
        float[] logits = forward(game).logits;

        // Numerically stable softmax (subtract max before exp)
        float maxLogit = Float.NEGATIVE_INFINITY;
        for (float l : logits) {
            if (l > maxLogit) maxLogit = l;
        }
        float sumExp = 0f;
        float[] probs = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            probs[i] = (float) Math.exp(logits[i] - maxLogit);
            sumExp += probs[i];
        }
        for (int i = 0; i < probs.length; i++) {
            probs[i] /= sumExp;
        }

        // Mask to legal cards; card logit index = pgx card index (0–35)
        EnumMap<Card, Float> masked = new EnumMap<>(Card.class);
        float sum = 0f;
        for (Card card : legalCards) {
            int pgxIdx = PgxFeatureEncoder.pgxIndex(card);
            float p = probs[pgxIdx];
            masked.put(card, p);
            sum += p;
        }

        if (sum > 0f) {
            final float finalSum = sum;
            masked.replaceAll((card, p) -> p / finalSum);
        } else {
            // Fallback: uniform over legal moves
            float uniform = 1f / legalCards.size();
            legalCards.forEach(card -> masked.put(card, uniform));
        }

        return masked;
    }

    // ── Trump head ────────────────────────────────────────────────────────────

    /** First trump-declaration logit index; {@code 36+code} for mode code 0–5, 42 for Schiebe. */
    private static final int TRUMP_LOGIT_OFFSET = 36;
    /** Logit index for the Schiebe (shift) action. */
    private static final int SCHIEBE_LOGIT = 42;

    /**
     * Returns a probability distribution over the legal trump declarations for the pre-trump
     * observation of {@code session}, suitable for raw (search-free) trump selection.
     *
     * <p>Softmaxes over the legal trump logits (indices 36–42 of the 43-way policy head:
     * {@code 36+code} for the six modes ♦♥♠♣/Obenabe/Undeufe, 42 for Schiebe) and returns the
     * result keyed by {@link Mode}. Schiebe is a legal action only when the forehand has not yet
     * passed (i.e. {@code !shifted}); the six declarations are always legal.
     *
     * @param session a fully determinized session in the trumpf-selection phase
     * @param shifted whether the forehand already passed (removes Schiebe from the legal set)
     * @return probability distribution over the legal {@link Mode}s
     */
    public Map<Mode, Float> predictTrumpPriorOverLegal(GameSession session, boolean shifted) {
        float[] logits = forward(PgxFeatureEncoder.encodeTrumpSelection(session, shifted)).logits;

        // Legal trump actions: the six declarations always, Schiebe only before a pass.
        List<Mode> legalModes = new java.util.ArrayList<>(7);
        int[] legalLogitIndex = new int[shifted ? 6 : 7];
        for (int code = 0; code <= 5; code++) {
            legalModes.add(Mode.from(code));
            legalLogitIndex[code] = TRUMP_LOGIT_OFFSET + code;
        }
        if (!shifted) {
            legalModes.add(Mode.shift());
            legalLogitIndex[6] = SCHIEBE_LOGIT;
        }

        // Numerically stable softmax over the legal-action logits only.
        float maxLogit = Float.NEGATIVE_INFINITY;
        for (int idx : legalLogitIndex) {
            if (logits[idx] > maxLogit) maxLogit = logits[idx];
        }
        float sumExp = 0f;
        float[] exp = new float[legalLogitIndex.length];
        for (int i = 0; i < legalLogitIndex.length; i++) {
            exp[i] = (float) Math.exp(logits[legalLogitIndex[i]] - maxLogit);
            sumExp += exp[i];
        }

        Map<Mode, Float> probs = new HashMap<>();
        for (int i = 0; i < legalModes.size(); i++) {
            probs.put(legalModes.get(i), exp[i] / sumExp);
        }
        return probs;
    }

    // ── Internal result type ──────────────────────────────────────────────────

    private static final class ForwardResult {
        /** Raw action logits: indices 0–35 are card plays (pgx order), 36–42 are trump/Schiebe. */
        final float[] logits;
        /** Predicted score differential / TARGET_SCALE; multiply by TARGET_SCALE to get points. */
        final float value;

        ForwardResult(float[] logits, float value) {
            this.logits = logits;
            this.value = value;
        }
    }
}
