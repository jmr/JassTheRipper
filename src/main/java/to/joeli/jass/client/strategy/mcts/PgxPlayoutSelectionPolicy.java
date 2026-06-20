package to.joeli.jass.client.strategy.mcts;

import to.joeli.jass.client.game.Game;
import to.joeli.jass.client.strategy.helpers.CardSelectionHelper;
import to.joeli.jass.client.strategy.helpers.PerfectInformationGameSolver;
import to.joeli.jass.client.strategy.mcts.src.Board;
import to.joeli.jass.client.strategy.mcts.src.Move;
import to.joeli.jass.client.strategy.mcts.src.PlayoutSelectionPolicy;
import to.joeli.jass.client.strategy.mcts.src.PuctPriorPolicy;
import to.joeli.jass.client.strategy.training.networks.PgxPolicyValueEstimator;
import to.joeli.jass.game.cards.Card;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * PUCT prior backed by the pgx {@code PolicyValueNet} policy head.
 *
 * <p>Implements {@link PuctPriorPolicy}: {@link #priors} runs the policy head on the
 * determinized board position and returns the full softmax distribution {@code P(s,a)}
 * over the legal cards. {@code MCTS.findChildrenPuct} uses this distribution directly as
 * the PUCT prior — preserving the calibrated distribution shape, which is where the
 * network's generation-to-generation gains live.
 *
 * <p>Also implements {@link PlayoutSelectionPolicy#getBestMove} (the argmax of {@link #priors})
 * for backward compatibility with the older argmax-"tip" prior path and for use as a
 * heuristic move picker.
 *
 * <p>{@link #runPlayout} delegates to the heavy rule-based heuristic, since this
 * class is intended only as a prior and not as the simulation policy.
 */
public class PgxPlayoutSelectionPolicy implements PlayoutSelectionPolicy, PuctPriorPolicy {

    private final PgxPolicyValueEstimator estimator;
    private final HeavyJassPlayoutSelectionPolicy fallback = new HeavyJassPlayoutSelectionPolicy();

    public PgxPlayoutSelectionPolicy(PgxPolicyValueEstimator estimator) {
        this.estimator = estimator;
    }

    /**
     * Returns the full policy prior {@code P(s,a)} over the legal cards as a map of
     * {@link CardMove} to probability, for use as the PUCT prior.
     *
     * <p>Returns an empty map (caller falls back to a uniform prior) if:
     * <ul>
     *   <li>the board is not a {@link JassBoard} (e.g. during trump selection),</li>
     *   <li>no game is attached (trump-selection phase),</li>
     *   <li>the legal-card set is empty.</li>
     * </ul>
     */
    @Override
    public Map<Move, Double> priors(Board board) {
        if (!(board instanceof JassBoard)) {
            return Collections.emptyMap();
        }
        Game game = ((JassBoard) board).getGame();
        if (game == null) {
            return Collections.emptyMap();   // trump-selection phase
        }

        Set<Card> playerCards = game.getCurrentPlayer().getCards();
        if (playerCards.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<Card> legal = CardSelectionHelper.getCardsPossibleToPlay(
                EnumSet.copyOf(playerCards), game);
        if (legal.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Card, Float> prior = estimator.predictPriorOverLegal(game, legal);
        Map<Move, Double> result = new HashMap<>();
        for (Map.Entry<Card, Float> entry : prior.entrySet()) {
            result.put(new CardMove(game.getCurrentPlayer(), entry.getKey()), entry.getValue().doubleValue());
        }
        return result;
    }

    /**
     * Returns the move with the highest policy probability among the legal cards (argmax
     * of {@link #priors}), or the heavy-heuristic move when the policy is unavailable
     * (e.g. trump selection or a non-{@link JassBoard}).
     */
    @Override
    public Move getBestMove(Board board) {
        Map<Move, Double> priors = priors(board);
        if (priors.isEmpty()) {
            return fallback.getBestMove(board);
        }
        return priors.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow();  // priors is non-empty here
    }

    /**
     * Delegates to the heavy rule-based heuristic for simulation playouts.
     * This method is not used when the policy is only set as a PUCT prior.
     */
    @Override
    public CardMove runPlayout(Game game) {
        return PerfectInformationGameSolver.runHeavyPlayout(game);
    }

    @Override
    public String toString() {
        return "pgx policy prior";
    }
}
