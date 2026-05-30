package to.joeli.jass.client.strategy.training.networks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tensorflow.ndarray.StdArrays;
import org.tensorflow.types.TFloat32;
import to.joeli.jass.client.game.Game;
import to.joeli.jass.client.game.Player;
import to.joeli.jass.client.strategy.helpers.CardKnowledgeBase;
import to.joeli.jass.client.strategy.helpers.Distribution;
import to.joeli.jass.client.strategy.helpers.NeuralNetworkHelper;
import to.joeli.jass.client.strategy.training.NetworkType;
import to.joeli.jass.game.cards.Card;

import java.util.*;

public class CardsEstimator extends NeuralNetwork {

	public static final Logger logger = LoggerFactory.getLogger(CardsEstimator.class);

	public CardsEstimator(boolean trainable) {
		super(NetworkType.CARDS, trainable);
	}


	/**
	 * Predict the card distribution of the hidden cards of the other players.
	 * This should help generating determinizations of better quality.
	 *
	 * @return
	 */
	public Map<Card, Distribution> predictCardDistribution(Game game, Set<Card> availableCards) {
		Map<Card, Distribution> cardKnowledge = CardKnowledgeBase.initCardKnowledge(game, availableCards);

		try (TFloat32 result = (TFloat32) predict(NeuralNetworkHelper.getCardsFeatures(game, cardKnowledge))) {
			final float[][] probabilities = tensorToFloats(result);
			return addNetworkPredictionToCardKnowledge(game, cardKnowledge, probabilities);
		}
	}

	private float[][] tensorToFloats(TFloat32 result) {
		// Result shape is (1, 36, 4); extract the first batch element.
		float[][][] res = StdArrays.array3dCopyOf(result);
		return res[0];
	}

	/**
	 * Here we take the information from the card knowledge we know for sure
	 * and replace the variable, guessed part with the estimation of the neural network
	 */
	private Map<Card, Distribution> addNetworkPredictionToCardKnowledge(Game game, Map<Card, Distribution> cardKnowledge, float[][] probabilities) {
		updateCardDistributions(game, cardKnowledge, probabilities, false);
		return cardKnowledge;
	}

	/**
	 * Re-runs inference using the current partial cardKnowledge as input features (which may contain
	 * 1-hot certainties for already-sampled cards). Updates only unsampled distributions in-place.
	 * This enables auto-regressive conditioning: after assigning some cards, the network can condition
	 * on those assignments when predicting the remaining ones.
	 */
	public void refineCardDistribution(Game game, Map<Card, Distribution> cardKnowledge) {
		try (TFloat32 result = (TFloat32) predict(NeuralNetworkHelper.getCardsFeatures(game, cardKnowledge))) {
			updateCardDistributions(game, cardKnowledge, tensorToFloats(result), true);
		}
	}

	/**
	 * Replaces each unsampled (or all, if skipSampled=false) cardKnowledge entry with a new
	 * Distribution built from the network's per-(card, player) probabilities. Players excluded
	 * by the old distribution are dropped and their probability rebalanced onto the rest.
	 */
	private void updateCardDistributions(Game game, Map<Card, Distribution> cardKnowledge, float[][] probabilities, boolean skipSampled) {
		final List<Player> players = game.getPlayersBySeatId();
		final Card[] cards = Card.values();
		for (int c = 0; c < cards.length; c++) {
			final Distribution oldDistribution = cardKnowledge.get(cards[c]);
			if (oldDistribution == null) continue;
			if (skipSampled && oldDistribution.isSampled()) continue;
			HashMap<Player, Float> playerProbabilities = new HashMap<>();
			List<Player> playersWithZeroProbabilities = new ArrayList<>();
			for (int p = 0; p < players.size(); p++) {
				final Player player = players.get(p);
				if (!oldDistribution.hasPlayer(player))
					playersWithZeroProbabilities.add(player);
				playerProbabilities.put(player, probabilities[c][p]);
			}
			final Distribution distribution = new Distribution(playerProbabilities, oldDistribution.isSampled());
			playersWithZeroProbabilities.forEach(distribution::deleteEventAndReBalance);
			cardKnowledge.put(cards[c], distribution);
		}
	}
}
