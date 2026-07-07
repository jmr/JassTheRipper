package to.joeli.jass.client.strategy.training;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import to.joeli.jass.client.game.*;
import to.joeli.jass.client.strategy.config.Config;
import to.joeli.jass.client.strategy.helpers.*;
import to.joeli.jass.client.strategy.training.data.CardsDataSet;
import to.joeli.jass.client.strategy.training.data.DataSet;
import to.joeli.jass.client.strategy.training.data.ScoreDataSet;
import to.joeli.jass.client.strategy.mcts.PgxPlayoutSelectionPolicy;
import to.joeli.jass.client.strategy.training.networks.NeuralNetwork;
import to.joeli.jass.client.strategy.training.networks.PgxPolicyValueEstimator;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.mode.Mode;

import java.io.File;
import java.util.*;

import static to.joeli.jass.client.strategy.training.data.DataSet.zeroPadded;

public class Arena {

	private static final boolean DATA_AUGMENTATION_ENABLED = true;
	private static final int NUM_EPISODES = 1000; // TEST: 1

	private static final int NUM_GAMES_TEST_SET = 20;
	private static final int NUM_GAMES_TRAIN_SET = 100;
	private static final int NUM_GAMES_VAL_SET = 10;
	// TEST: 2, Needs to be an even number because of fairTournamentMode!
	private static final int NUM_EVALUATION_GAMES = 10;

	// If the learning network scores more points than the frozen network times this factor, the frozen network gets replaced
	public static final double IMPROVEMENT_THRESHOLD_PERCENTAGE = 105;
	public static final int SEED = 42;
	public static final float TOTAL_POINTS = 157.0f; // INFO: We disregard Matchbonus for simplicity here

	private static final boolean CARDS_ESTIMATOR_USED = false;
	private static final boolean SCORE_ESTIMATOR_USED = false;


	private final double improvementThresholdPercentage;
	private final Random random;

	private double minValLossOld = Double.MAX_VALUE;
	private double testLossOld = Double.MAX_VALUE;

	private GameSession gameSession;

	private CardsDataSet cardsDataSet;
	private ScoreDataSet scoreDataSet;

	public static final Logger logger = LoggerFactory.getLogger(Arena.class);
	public static final Logger experimentLogger = LoggerFactory.getLogger("Experiment");
	public static final Logger resultLogger = LoggerFactory.getLogger("Result");
	public static final Logger statsLogger = LoggerFactory.getLogger("Stats");

	public static void main(String[] args) {
		final Arena arena = new Arena(IMPROVEMENT_THRESHOLD_PERCENTAGE, SEED);

		logger.info("Training the networks with self-play\n");
		arena.trainForNumEpisodes(NUM_EPISODES);
	}

	public Arena(double improvementThresholdPercentage, int seed) {
		this(improvementThresholdPercentage, seed, true);
	}

	public Arena(double improvementThresholdPercentage, int seed, boolean runSetUp) {
		// CudaEnvironment.getInstance().getConfiguration().allowMultiGPU(true); // NOTE: This might have to be enabled on the server

		this.improvementThresholdPercentage = improvementThresholdPercentage;
		random = new Random(seed);

		if (runSetUp) setUp();
		else gameSession = GameSessionBuilder.newSession().createGameSession();
	}

	public Arena(GameSession gameSession) {
		this(IMPROVEMENT_THRESHOLD_PERCENTAGE, SEED);
		this.gameSession = gameSession;
	}

	private void setUp() {
		logger.info("Setting up the training process\n");
		gameSession = GameSessionBuilder.newSession().createGameSession();

		// The Datasets operate with an evicting queue. When a new element is added and the queue is full, the head is removed.
		cardsDataSet = new CardsDataSet(computeDataSetSize());
		scoreDataSet = new ScoreDataSet(computeDataSetSize());

		String path = DataSet.getEpisodePath(0);
		if (!new File(path).exists()) {
			logger.info("No dataset found. Collecting a dataset of games played using MCTS with random playouts\n");
			runMCTSWithRandomPlayout();
		}

		logger.info("Existing dataset found. Pre-training the neural networks\n");
		trainNetworks(0);

		logger.info("Loading the pre-trained networks into memory\n");
		Config[] configs = {
				new Config(true, CARDS_ESTIMATOR_USED, true, SCORE_ESTIMATOR_USED, true),
				new Config(true, CARDS_ESTIMATOR_USED, false, SCORE_ESTIMATOR_USED, false)
		};
		gameSession.setConfigs(configs);
		loadNetworks(0, true);
		loadNetworks(0, false);
	}

	public void trainForNumEpisodes(int numEpisodes) {
		List<Double> history = new ArrayList<>();
		history.add(0.0); // no performance for pre-training
		for (int i = 1; i < numEpisodes; i++) {
			history.add(i, runEpisode(i));
		}
		logger.info("Performance over the episodes:\n{}", history);
	}

	public void trainUntilBetterThanRandomPlayouts() {
		List<Double> history = new ArrayList<>(Collections.singletonList(0.0));
		history.add(0.0); // no performance for pre-training
		for (int i = 1; history.get(i) < 100; i++) {
			history.add(i, runEpisode(i));
		}
		logger.info("Performance over the episodes:\n{}", history);
	}

	/**
	 * Runs an episode with the following parts:
	 * - Self play with score estimation and mcts policy improvement to collect experiences into the replay buffer
	 * - Trains the network with the recorded games from the replay buffer
	 * - Pits the networks against each other without the mcts policy improvement to see which one performs better
	 * - If the learning network can outperform the frozen network by an improvementThresholdPercentage, the frozen one is updated
	 * - Tests the performance of mcts with score estimation by playing against mcts with random playouts
	 *
	 * @return the performance of mcts with score estimation against mcts with random playouts
	 */
	private double runEpisode(int episode) {
		logger.info("Running episode #{}\n", episode);
		experimentLogger.info("\n===========================\nEpisode #{}\n===========================", episode);

		logger.info("Collecting training examples by self play with estimator enhanced MCTS\n");
		runMCTSWithEstimators(episode);

		logger.info("Training the trainable networks with the collected examples\n");
		trainNetworks(episode);

		logger.info("Loading the newly trained trainable networks into memory\n");
		loadNetworks(episode, true);

		if (SCORE_ESTIMATOR_USED) {
			logger.info("Pitting the 'naked' score estimators against each other to see " +
					"if the learning network can score more than {}% of the points of the frozen network\n", improvementThresholdPercentage);
			final boolean wasImproved = runOnlyNetworks() > improvementThresholdPercentage;
			updateNetworks(episode, wasImproved);
		}
		if (CARDS_ESTIMATOR_USED) {
			logger.info("Checking if the minimum validation loss of the current cards estimator is less than the old one\n");
			double minValLoss = IOHelper.INSTANCE.getQuantifierFromFile("min_val_loss.txt");
			experimentLogger.info("\nMinimum Validation Loss after training network: {}", minValLoss);
			minValLossOld = minValLoss;
			double testLoss = IOHelper.INSTANCE.getQuantifierFromFile("test_loss.txt");
			experimentLogger.info("\nTest Loss after training network: {}", testLoss);
			updateNetworks(episode, testLoss < testLossOld);
			testLossOld = testLoss;
		}

		logger.info("Testing MCTS with estimators against basic MCTS with random playout\n");
		final double performance = runMCTSWithEstimatorsAgainstMCTSWithoutEstimators();
		experimentLogger.info("\nEstimator enhanced MCTS scored {}% of the points of regular MCTS", performance);

		logger.info("After episode #{}, estimator enhanced MCTS scored {}% of the points of regular MCTS\n", episode, performance);
		return performance;
	}

	private void updateNetworks(int episodeNumber, boolean wasImproved) {
		if (wasImproved) { // if the learning network is significantly better
			logger.info("The learning network outperformed the frozen network. Updating the frozen network\n");
			loadNetworks(episodeNumber, false);
		} else {
			logger.info("The learning network failed to outperform the frozen network. Training for another episode\n");
		}
	}

	/**
	 * Loads the exported models into memory.
	 */
	private void loadNetworks(int episodeNumber, boolean trainable) {
		if (CARDS_ESTIMATOR_USED) {
			gameSession.getPlayersInInitialPlayingOrder().forEach(player -> {
				if (player.getConfig().isCardsEstimatorTrainable() == trainable)
					player.getCardsEstimator().loadModel(episodeNumber);
			});
		}
		if (SCORE_ESTIMATOR_USED) {
			gameSession.getPlayersInInitialPlayingOrder().forEach(player -> {
				if (player.getConfig().isScoreEstimatorTrainable() == trainable)
					player.getScoreEstimator().loadModel(episodeNumber);
			});
		}
	}

	/**
	 * Trains the networks
	 */
	private void trainNetworks(int episode) {
		// NOTE: The networks of team 0 are trainable. Both players of the same team normally have the same network references
		if (CARDS_ESTIMATOR_USED)
			NeuralNetwork.train(episode, NetworkType.CARDS);
		if (SCORE_ESTIMATOR_USED)
			NeuralNetwork.train(episode, NetworkType.SCORE);
	}

	public double runMatchWithConfigs(Config[] configs) {
		return runMatchWithConfigs(configs, NUM_EVALUATION_GAMES);
	}

	public double runMatchWithConfigs(Config[] configs, int numGames) {
		resultLogger.info("{}", configs[0]);
		resultLogger.info("{}", configs[1]);
		resultLogger.info("Number of evaluation games: {}", numGames);
		resultLogger.info("Number of double games: {}", numGames / 2);

		// Load pgx estimators before setConfigs so that PgxPlayoutSelectionPolicy can be
		// installed into MCTSConfig.puctPriorPolicy before MCTSHelper is constructed.
		PgxPolicyValueEstimator[] estimators = new PgxPolicyValueEstimator[configs.length];
		for (int i = 0; i < configs.length; i++) {
			String pgxPath = configs[i].getPgxModelPath();
			if (pgxPath != null && (configs[i].isPgxValueUsed() || configs[i].isPgxPolicyUsed() || configs[i].isPgxRawPlayUsed() || configs[i].isPgxTrumpUsed())) {
				estimators[i] = new PgxPolicyValueEstimator();
				estimators[i].loadModel(pgxPath);
				if (configs[i].isPgxPolicyUsed()) {
					configs[i].getMctsConfig().setPuctEnabled(true);
					configs[i].getMctsConfig().setPuctPriorPolicy(
							new PgxPlayoutSelectionPolicy(estimators[i]));
				}
			}
		}

		gameSession.setConfigs(configs);  // MCTSHelpers created here; see puctPriorPolicy above

		for (int i = 0; i < configs.length; i++) {
			if (estimators[i] != null) {
				for (Player player : gameSession.getPlayersOfTeam(i)) {
					player.setPgxEstimator(estimators[i]);
				}
			}
		}
		try {
			return playGames(numGames, TrainMode.EVALUATION, null, -1);
		} finally {
			// Release each player's MCTS thread pools (non-daemon) so callers — e.g. the
			// ApplicationArena CLI — can exit instead of hanging on lingering pool threads.
			shutDownStrategies();
		}
	}

	/** Shuts down every player's strategy, releasing the MCTS thread pools held this match. */
	private void shutDownStrategies() {
		for (int team = 0; team < 2; team++)
			for (Player player : gameSession.getPlayersOfTeam(team))
				player.getJassStrategy().shutDown();
	}

	private double runOnlyNetworks() {
		Config[] configs = {
				new Config(false, CARDS_ESTIMATOR_USED, true, SCORE_ESTIMATOR_USED, true),
				new Config(false, CARDS_ESTIMATOR_USED, false, SCORE_ESTIMATOR_USED, false)
		};
		return performMatch(TrainMode.EVALUATION, -1, configs);
	}

	private double runMCTSWithEstimatorsAgainstMCTSWithoutEstimators() {
		Config[] configs = {
				new Config(true, CARDS_ESTIMATOR_USED, true, SCORE_ESTIMATOR_USED, true),
				new Config(true, false, false, false, false)
		};
		return performMatch(TrainMode.EVALUATION, -1, configs);
	}

	private double runMCTSWithRandomPlayout() {
		Config[] configs = {
				new Config(true, false, false, false, false),
				new Config(true, false, false, false, false)
		};
		return performMatch(TrainMode.DATA_COLLECTION, 0, configs);
	}

	private double runMCTSWithEstimators(int episode) {
		Config[] configs = {
				new Config(true, CARDS_ESTIMATOR_USED, true, SCORE_ESTIMATOR_USED, true),
				new Config(true, CARDS_ESTIMATOR_USED, false, SCORE_ESTIMATOR_USED, false)
		};
		return performMatch(TrainMode.DATA_COLLECTION, episode, configs);
	}

	/**
	 * Performs a match which can be parametrized along multiple dimensions to test the things we want
	 *
	 * @param trainMode
	 * @param configs
	 * @return
	 */
	private double performMatch(TrainMode trainMode, int episode, Config[] configs) {
		gameSession.setConfigs(configs);

		if (trainMode == TrainMode.DATA_COLLECTION) {
			logger.info("Collecting training set\n");
			playGames(NUM_GAMES_TRAIN_SET, trainMode, "train/", episode);

			logger.info("Collecting validation set\n");
			playGames(NUM_GAMES_VAL_SET, trainMode, "val/", episode);

			logger.info("Collecting test set\n");
			playGames(NUM_GAMES_TEST_SET, trainMode, "test/", episode);
		}
		if (trainMode == TrainMode.EVALUATION)
			return playGames(NUM_EVALUATION_GAMES, trainMode, null, episode);
		return 0;
	}

	/**
	 * Simulates a number of games and returns the performance of Team A in comparison with Team B.
	 *
	 * @param numGames
	 * @param trainMode
	 * @return
	 */
	private double playGames(int numGames, TrainMode trainMode, String dataSetType, int episode) {
		List<Card> orthogonalCards = null;
		List<Card> cards = Arrays.asList(Card.values());
		Collections.shuffle(cards, random);

		resultLogger.info("Team1,Team2");
		Result firstResult = null;
		List<Double> pairDiffs = new ArrayList<>();
		for (int i = 1; i <= numGames; i++) {
			logger.info("Running game #{}\n", i);

			if (trainMode.isFairTournamentModeEnabled())
				orthogonalCards = dealCards(cards, orthogonalCards);
			else {
				Collections.shuffle(cards, random);
				gameSession.dealCards(cards);
			}

			performTrumpfSelection();

			Result result = playGame(trainMode.isSavingData());

			logger.info("Result of game #{}: {}\n", i, result);
			if (trainMode.isFairTournamentModeEnabled()) {
				if (firstResult == null) {
					firstResult = result;
				} else {
					int pairA = result.getTeamAScore().getScore() + firstResult.getTeamAScore().getScore();
					int pairB = result.getTeamBScore().getScore() + firstResult.getTeamBScore().getScore();
					resultLogger.info("{},{}", pairA, pairB);
					pairDiffs.add((double) (pairA - pairB));
					firstResult = null;
				}
			} else {
				int diffA = result.getTeamAScore().getScore() - result.getTeamBScore().getScore();
				resultLogger.info("{}", diffA);
				pairDiffs.add((double) diffA);
			}

			if (trainMode.isSavingData())
				IOHelper.INSTANCE.saveData(cardsDataSet, scoreDataSet, episode, dataSetType, zeroPadded(i));
		}
		if (firstResult != null)
			resultLogger.warn("numGames={} is odd with fair-tournament mode enabled — last game result dropped (no pair)", numGames);

		gameSession.updateResult(); // normally called within gameSession.startNewGame(), so we need it at the end again
		final Result result = gameSession.getResult();
		logger.info("Aggregated result of the {} games played: {}\n", numGames, result);
		double improvement = Math.round(10000.0 * result.getTeamAScore().getScore() / result.getTeamBScore().getScore()) / 100.0;
		logger.info("Team A scored {}% of the points of Team B\n", improvement);

		if (!pairDiffs.isEmpty())
			printStats(pairDiffs);

		logger.info("Resetting the result so we can get a fresh start afterwards\n");
		gameSession.resetResult();

		return improvement;
	}

	private static void printStats(List<Double> diffs) {
		int n = diffs.size();
		double mean = diffs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
		double variance = diffs.stream().mapToDouble(d -> (d - mean) * (d - mean)).sum() / (n - 1);
		double se = Math.sqrt(variance / n);
		double t = se == 0 ? Double.NaN : mean / se;
		int df = n - 1;
		double tP = Double.isNaN(t) ? Double.NaN : tTestTwoSidedP(t, df);

		long wins = diffs.stream().filter(d -> d > 0).count();
		long losses = diffs.stream().filter(d -> d < 0).count();
		long nEff = wins + losses; // exclude ties (pair diffs can be 0 when pairA == 157)
		double signP = nEff > 0 ? signTestTwoSidedP(wins, nEff) : Double.NaN;

		long ties = n - nEff;
		statsLogger.info("--- Stats ({} pairs) ---", n);
		statsLogger.info(String.format("Paired t-test: mean_diff=%.1f  sd=%.1f  t=%.3f  df=%d  p=%.4f", mean, Math.sqrt(variance), t, df, tP));
		statsLogger.info(String.format("Sign test:     wins=%d  losses=%d  ties=%d  p=%.4f", wins, losses, ties, signP));
	}

	// Two-sided p-value for a paired t-test: P(|T| >= |t|) under H0
	private static double tTestTwoSidedP(double t, int df) {
		double x = (double) df / (df + t * t);
		return regularizedIncompleteBeta(df / 2.0, 0.5, x);
	}

	// Two-sided binomial sign test: P(X >= wins or X <= losses) under H0: p=0.5, X~Bin(nEff, 0.5)
	private static double signTestTwoSidedP(long wins, long nEff) {
		long k = Math.max(wins, nEff - wins);
		double tail = 0;
		for (long i = k; i <= nEff; i++)
			tail += Math.exp(logBinomCoef(nEff, i) + nEff * Math.log(0.5));
		return Math.min(1.0, 2 * tail);
	}

	private static double logBinomCoef(long n, long k) {
		if (k == 0 || k == n) return 0;
		return lgamma(n + 1) - lgamma(k + 1) - lgamma(n - k + 1);
	}

	// Numerical helpers below (Lanczos lgamma + Lentz CF for incomplete beta).
	// Alternative: replace with commons-math3 TDistribution / BinomialDistribution — cleaner but adds ~1 MB dep.

	// Regularized incomplete beta I_x(a,b) via Lentz continued fraction
	private static double regularizedIncompleteBeta(double a, double b, double x) {
		if (x <= 0) return 0;
		if (x >= 1) return 1;
		if (x > (a + 1) / (a + b + 2))
			return 1 - regularizedIncompleteBeta(b, a, 1 - x);
		double logBeta = lgamma(a) + lgamma(b) - lgamma(a + b);
		double front = Math.exp(a * Math.log(x) + b * Math.log(1 - x) - logBeta) / a;
		return front * betaContinuedFraction(a, b, x);
	}

	private static double betaContinuedFraction(double a, double b, double x) {
		final double FPMIN = 1e-30;
		double qab = a + b, qap = a + 1, qam = a - 1;
		double c = 1, d = 1 - qab * x / qap;
		if (Math.abs(d) < FPMIN) d = FPMIN;
		d = 1 / d;
		double h = d;
		for (int m = 1; m <= 200; m++) {
			int m2 = 2 * m;
			double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
			d = 1 + aa * d; if (Math.abs(d) < FPMIN) d = FPMIN;
			c = 1 + aa / c; if (Math.abs(c) < FPMIN) c = FPMIN;
			d = 1 / d; h *= d * c;
			aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
			d = 1 + aa * d; if (Math.abs(d) < FPMIN) d = FPMIN;
			c = 1 + aa / c; if (Math.abs(c) < FPMIN) c = FPMIN;
			d = 1 / d;
			double del = d * c; h *= del;
			if (Math.abs(del - 1) < 3e-7) break;
		}
		return h;
	}

	// Lanczos log-gamma, accurate to ~15 significant digits for x > 0
	private static double lgamma(double x) {
		double[] c = {0.99999999999980993, 676.5203681218851, -1259.1392167224028,
				771.32342877765313, -176.61502916214059, 12.507343278686905,
				-0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};
		if (x < 0.5)
			return Math.log(Math.PI / Math.sin(Math.PI * x)) - lgamma(1 - x);
		x -= 1;
		double a = c[0];
		for (int i = 1; i < c.length; i++) a += c[i] / (x + i);
		double t = x + 7.5;
		return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
	}

	/**
	 * Simulates a game being played. The gameSession can be configured in many different ways to allow different simulations.
	 *
	 * @param savingData
	 * @return
	 */
	public Result playGame(boolean savingData) {
		Game game = gameSession.getCurrentGame();

		HashMap<float[][], Player> scoreFeaturesForPlayer = new HashMap<>(); // The float[][] is the key because it is unique
		int[][] cardsTarget = null;
		List<int[][]> analogousCardsTargets = new ArrayList<>();
		if (savingData) {
			if (DATA_AUGMENTATION_ENABLED) {
				analogousCardsTargets = NeuralNetworkHelper.getAnalogousCardsTargets(game);
			} else {
				cardsTarget = NeuralNetworkHelper.getCardsTarget(game);
			}
		}

		while (!game.gameFinished()) {
			final Round round = game.getCurrentRound();
			while (!round.roundFinished()) {
				final Player player = game.getCurrentPlayer();
				final Move move = player.makeMove(gameSession);
				gameSession.makeMove(move);
				player.onMoveMade(move);

				if (savingData && !game.getCurrentPlayer().getCards().isEmpty()) {
					final Map<Card, Distribution> cardKnowledge = CardKnowledgeBase.initCardKnowledge(game, game.getCurrentPlayer().getCards());
					if (DATA_AUGMENTATION_ENABLED) {
						// INFO: Because the permutations are always in the same order we can just add the analogous features and targets. They
						cardsDataSet.addFeatures(NeuralNetworkHelper.getAnalogousCardsFeatures(game, cardKnowledge));
						cardsDataSet.addTargets(analogousCardsTargets);

						NeuralNetworkHelper.getAnalogousScoreFeatures(game).forEach(feature -> scoreFeaturesForPlayer.put(feature, player));
					} else {
						cardsDataSet.addFeature(NeuralNetworkHelper.getCardsFeatures(game, cardKnowledge));
						cardsDataSet.addTarget(cardsTarget);

						scoreFeaturesForPlayer.put(NeuralNetworkHelper.getScoreFeatures(game), player);
					}
				}
			}
			gameSession.startNextRound();
		}

		if (savingData) {
			if (scoreFeaturesForPlayer.size() > computeDataSetSize()) throw new AssertionError();
			for (Map.Entry<float[][], Player> entry : scoreFeaturesForPlayer.entrySet()) {
				scoreDataSet.addFeature(entry.getKey());
				scoreDataSet.addTarget(NeuralNetworkHelper.getScoreTarget(game, entry.getValue()));
			}
		}

		return game.getResult();
	}

	private int computeDataSetSize() {
		// 36: Number of Cards in a game
		int size = 36;
		if (DATA_AUGMENTATION_ENABLED)
			size *= 24; // 24: Number of color permutations
		return size;
	}

	/**
	 * Deals the cards to the players based on a random seed.
	 * "Orthogonal" cards (List is rotated by 9: team 1 gets cards of team 2 and vice versa) are returned for use in the next game (fairness!)
	 *
	 * @param normalCards
	 * @param orthogonalCards
	 * @return
	 */
	private List<Card> dealCards(List<Card> normalCards, List<Card> orthogonalCards) {
		if (orthogonalCards == null) {
			logger.info("Dealing the 'normal' cards: {}\n", normalCards);
			gameSession.dealCards(normalCards);

			// And prepare orthogonal cards
			orthogonalCards = new ArrayList<>(normalCards);
			Collections.rotate(orthogonalCards, 9); // rotate list so that the opponents now have the cards we had before and vice versa --> this ensures fair testing!
		} else { // if we have orthogonal cards
			logger.info("Dealing the 'orthogonal' cards: {}\n", orthogonalCards);
			gameSession.dealCards(orthogonalCards);

			// And prepare normal cards again
			orthogonalCards = null;
			Collections.shuffle(normalCards, random);
		}
		return orthogonalCards;
	}


	/**
	 * Organizes the trumpf selection part of the simulation.
	 */
	private void performTrumpfSelection() {
		boolean shifted = false;
		Player currentPlayer = gameSession.getTrumpfSelectingPlayer();
		Mode mode = currentPlayer.chooseTrumpf(gameSession, false);

		if (mode.equals(Mode.shift())) {
			shifted = true;
			final Player partner = gameSession.getPartnerOfPlayer(currentPlayer);
			mode = partner.chooseTrumpf(gameSession, true);
		}
		gameSession.startNewGame(mode, shifted);
	}
}
