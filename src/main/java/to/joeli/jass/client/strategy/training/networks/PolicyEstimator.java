package to.joeli.jass.client.strategy.training.networks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tensorflow.ndarray.StdArrays;
import org.tensorflow.types.TFloat32;
import to.joeli.jass.client.game.Game;
import to.joeli.jass.client.strategy.helpers.CardKnowledgeBase;
import to.joeli.jass.client.strategy.helpers.Distribution;
import to.joeli.jass.client.strategy.helpers.NeuralNetworkHelper;
import to.joeli.jass.client.strategy.training.NetworkType;
import to.joeli.jass.game.cards.Card;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * AlphaZero-style policy head: input state, output softmax distribution over the 36 cards.
 * Used as a PUCT prior in {@code MCTS.findChildrenPuct}.
 *
 * <p>Training target is the MCTS visit distribution (normalized) at the root, collected during
 * self-play in {@link to.joeli.jass.client.strategy.training.Arena}.</p>
 *
 * <p>At inference time, the raw 36-vector is masked to the legal moves at the current position
 * and renormalized — this is the proper probability distribution suitable for PUCT's {@code P(a)}
 * term, with no stochastic-sampling noise (unlike {@code LightJassPlayoutSelectionPolicy} et al).</p>
 */
public class PolicyEstimator extends NeuralNetwork {

	public static final Logger logger = LoggerFactory.getLogger(PolicyEstimator.class);

	public PolicyEstimator(boolean trainable) {
		super(NetworkType.POLICY, trainable);
	}

	/**
	 * Predict an unmasked policy: softmax probabilities over all 36 cards. Use
	 * {@link #predictPriorOverLegal} for the PUCT-ready masked-and-renormalized version.
	 */
	public float[] predictPolicy(Game game) {
		Map<Card, Distribution> cardKnowledge = CardKnowledgeBase.initCardKnowledge(
				game, game.getCurrentPlayer().getCards());
		try (TFloat32 result = predict(NeuralNetworkHelper.getCardsFeatures(game, cardKnowledge))) {
			float[][] res = StdArrays.array2dCopyOf(result);
			return res[0];
		}
	}

	/**
	 * Predict the policy masked to legal cards and renormalized. Returns a proper probability
	 * distribution over {@code legalCards} (sums to 1, zeros elsewhere). Suitable as the
	 * {@code P(a)} term in PUCT.
	 */
	public Map<Card, Float> predictPriorOverLegal(Game game, Set<Card> legalCards) {
		float[] policy = predictPolicy(game);
		EnumMap<Card, Float> masked = new EnumMap<>(Card.class);
		Card[] cards = Card.values();
		float sum = 0f;
		for (Card card : legalCards) {
			float p = policy[card.ordinal()];
			masked.put(card, p);
			sum += p;
		}
		if (sum > 0f) {
			for (Map.Entry<Card, Float> entry : masked.entrySet()) {
				entry.setValue(entry.getValue() / sum);
			}
		} else {
			// Fallback: uniform over legal moves if the masked probabilities are all zero.
			float uniform = 1f / legalCards.size();
			for (Card card : legalCards) masked.put(card, uniform);
		}
		return masked;
	}
}
