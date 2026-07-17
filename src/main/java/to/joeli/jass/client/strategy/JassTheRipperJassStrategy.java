package to.joeli.jass.client.strategy;

import com.google.common.collect.Iterables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import to.joeli.jass.client.game.Game;
import to.joeli.jass.client.game.GameSession;
import to.joeli.jass.client.strategy.config.Config;
import to.joeli.jass.client.strategy.config.MCTSConfig;
import to.joeli.jass.client.strategy.config.StrengthLevel;
import to.joeli.jass.client.strategy.config.TrumpfSelectionMethod;
import to.joeli.jass.client.strategy.analysis.CardAnalysis;
import to.joeli.jass.client.strategy.analysis.PgxPositionAnalyzer;
import to.joeli.jass.client.strategy.analysis.TrumpAnalysis;
import to.joeli.jass.client.strategy.exceptions.MCTSException;
import to.joeli.jass.client.strategy.helpers.CardSelectionHelper;
import to.joeli.jass.client.strategy.helpers.MCTSHelper;
import to.joeli.jass.client.strategy.helpers.TrumpfSelectionHelper;
import to.joeli.jass.client.strategy.mcts.CardMove;
import to.joeli.jass.client.strategy.mcts.TrumpfMove;
import to.joeli.jass.client.strategy.mcts.src.Move;
import to.joeli.jass.client.strategy.mcts.src.PlayoutSelectionPolicy;
import to.joeli.jass.client.strategy.training.networks.CardsEstimator;
import to.joeli.jass.client.strategy.training.networks.PgxPolicyValueEstimator;
import to.joeli.jass.client.strategy.training.networks.ScoreEstimator;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.mode.Mode;

import java.util.Set;


/**
 * The main class for the famous JassTheRipper Jass Strategy.
 * <p>
 * Some jass terms are introduced here for clarification:
 * Brettli: A card with value 0
 * Bock: The highest card of a color
 * Stechen: Play a card such that I win the round.
 * Anziehen: (Nur bei Trumpf) Being the starting player in the round playing a Brettli in order to show my partner that I am good at this color (have either King or Queen but not Ace)
 * Verwerfen: (Nur bei Obeabe oder Undeufe) If my partner wins the round play a Brettli of a color in order to show him/her that I am weak at that color
 * Schmieren: If my partner wins the round play a valuable card to gain many points.
 */
public class JassTheRipperJassStrategy implements JassStrategy {

//	TODO refinejass knowledge schauen austrumpfen
/*
NOTES FROM PLAY AGAINST JASS THE RIPPER 13/02/2018:

250ms bedenkzeit

Negativ:
Austrumpfen suboptimal
lässt mich lange gewähren obwohl er trümpfe hat
abstechen passiv



2500ms bedenkzeit

Negativ:
Gegner spielt auf mein könig, obwohl er noch die 7 hatte -> regel hinzufügen
Gegner trumpft aus obwohl nur noch sein Partner trümpfe hat!!!!!!
Gegner sticht 10er nicht ab als letzter spieler obwohl er noch 3 trümpfe hat!!!
Partner sticht als zweitletzter spieler gegnerisches ass mit trumpf 10 ab und wird danach überstochen!!
Partner schmiert dem Gegner, obwohl er noch ein Brettli hat!
Trumpft mit nell aus in dritter runde obwohl bauer noch im spiel ist!


Neutral:
Spielt undeufe reihe von 6-9 in spezieller reihenfolge (6 zuletzt)
Partner hat bei undeufe 6 zurückgehalten als gegner 7 gespielt hat

Positiv:
behält trumpf für letzten stich
hat einen sehr guten trumpf gut erkannt
Schmieren funktioniert
Mit 2500ms bedenkzeit austrumpfen besser
partner hat angezogen beim ausspielen
gegner antizipiert bauer des partners bei austrumpfen meines partners indem er ein ass schmiert (2. stich!)
Mehr simulationen scheinen hilfreich zu sein (spielt viel aggressiver)
Partner probiert auf match zu jassen
Gegner gehen auf die grossen punkte am schluss des spiels bei undeufe
Gegner spielt auf match (sticht brettli stich mit brettli ab, so dass ich als letzter spieler den stich nicht gratis holen kann)
Partner hat mit mir Schellen Match erfolgreich durchgezogen
*/

/*
POSSIBLE ENHANCEMENTS FROM MCTS SURVEY:
Tune parameters: exploration constant
Weighing simulation results
architecture similar to alphazero with neural net
*/



/*
Voices from the experiments:
Seems fake. Opponents are better than partner
Partnerbot hat gegner ein ass geschmiert obwohl er ein brettli hätte spielen können
Gegner hat es 10i vo mir (höchsti charte) als zweitletzte nid gno (9i hetter ge), obwohl er ass und könig gha hett
gegner hat trumpf als 3.-4. charte usgspilt obwohl niemer meh trumpf gha het (bzw het müesse ageh)


TODO Make new experiments with the improvements so far:
 -> NOTES FROM PLAY AGAINST JASS THE RIPPER 25/06/2018:

 */

	// IDEA: only one stateless jasstheripper computation container which provides an api to be called

	// TODO consider ForkJoinPool so we can also do leaf parallelisation or tree parallelisation
	// TODO find a way to visualize the MCTS tree
	// TODO hilfsmethoden bockVonJederFarbe, TruempfeNochImSpiel, statistisches Modell von möglichen Karten von jedem Spieler -> neural network (CardsEstimator)
	// TODO add exceptions to code!!!
	// TODO add tests!
	// TODO select function mcts anschauen, wie wird leaf node bestimmt?

	private Config config = new Config();

	private MCTSHelper mctsHelper;

	private CardsEstimator cardsEstimator = new CardsEstimator(config.isCardsEstimatorTrainable());
	private ScoreEstimator scoreEstimator = new ScoreEstimator(config.isScoreEstimatorTrainable());
	private PgxPolicyValueEstimator pgxEstimator = null;
	private PgxPolicyValueEstimator pgxBeliefEstimator = null;


	public static final Logger logger = LoggerFactory.getLogger(JassTheRipperJassStrategy.class);

	public static JassTheRipperJassStrategy getTestInstance() {
		return new JassTheRipperJassStrategy(new Config(new MCTSConfig(StrengthLevel.FAST, StrengthLevel.FAST_TEST)));
	}

	public JassTheRipperJassStrategy() {
		setConfig(new Config());
	}

	public JassTheRipperJassStrategy(Config config) {
		setConfig(config);
	}

	@Override
	public Mode chooseTrumpf(Set<Card> availableCards, GameSession session, boolean shifted) {
		try {
			final long startTime = System.nanoTime();
			if (availableCards.size() != 9) {
				throw new IllegalStateException(String.format(
						"INVARIANT VIOLATED at chooseTrumpf: availableCards.size=%d, expected 9. " +
								"availableCards=%s",
						availableCards.size(), availableCards));
			}
			printCards(availableCards);

			Mode mode = TrumpfSelectionHelper.getRandomMode(shifted);

			// Raw pgx policy trump selection overrides the configured method when enabled.
			if (config.isPgxTrumpUsed() && getPgxEstimator() != null && getPgxEstimator().isLoaded()) {
				logger.info("Chose trumpf based on raw pgx policy (no search)");
				return choosePgxTrumpf(availableCards, session, shifted);
			}

			if (config.getTrumpfSelectionMethod() == TrumpfSelectionMethod.RULE_BASED)
				mode = TrumpfSelectionHelper.predictTrumpf(availableCards, shifted);

			if (config.getTrumpfSelectionMethod() == TrumpfSelectionMethod.MCTS)
				try {
					if (mctsHelper == null) throw new AssertionError();
					Move move = mctsHelper.predictMove(availableCards, session, true, shifted);
					mode = ((TrumpfMove) move).getChosenTrumpf();
				} catch (MCTSException e) {
					logger.error("{}", e);
					logger.error("Something went wrong. Had to choose random trumpf, damn it!");
				}

			if (config.getTrumpfSelectionMethod() == TrumpfSelectionMethod.MCTS_BEFORE_SHIFT)
				if (!shifted)
					try {
						if (mctsHelper == null) throw new AssertionError();
						Move move = mctsHelper.predictMove(availableCards, session, true, shifted);
						mode = ((TrumpfMove) move).getChosenTrumpf();
					} catch (MCTSException e) {
						logger.error("{}", e);
						mode = TrumpfSelectionHelper.predictTrumpf(availableCards, shifted);
					}
				else
					mode = TrumpfSelectionHelper.predictTrumpf(availableCards, shifted);

			if (config.getTrumpfSelectionMethod() == TrumpfSelectionMethod.MCTS_ON_SHIFT)
				if (shifted)
					try {
						if (mctsHelper == null) throw new AssertionError();
						Move move = mctsHelper.predictMove(availableCards, session, true, shifted);
						mode = ((TrumpfMove) move).getChosenTrumpf();
					} catch (MCTSException e) {
						logger.error("{}", e);
						mode = TrumpfSelectionHelper.predictTrumpf(availableCards, shifted);
					}
				else
					mode = TrumpfSelectionHelper.predictTrumpf(availableCards, shifted);

			logger.info("Total time for move: {}ms", (System.nanoTime() - startTime) / 1000000d);
			logger.info("Chose trumpf {}", mode);
			return mode;
		} catch (Exception e) {
			logger.error("{}", e);
			logger.error("Something unexpectedly went terribly wrong! But could catch exception and chose random trumpf now.");
			return TrumpfSelectionHelper.getRandomMode(shifted);
		}
	}


	@Override
	public Card chooseCard(Set<Card> availableCards, GameSession session) {
		final Game game = session.getCurrentGame();
		try {
			final long startTime = System.nanoTime();
			// Card-conservation invariant: bot's hand size must match round position exactly.
			// At chooseCard entry the bot hasn't played in the current round yet, so the
			// expected size is 9 - roundNumber. If this fires, the bot's hand desynced from
			// the actual game state — see CARD_DESYNC_BUG.md.
			final int roundNumber = game.getCurrentRound().getRoundNumber();
			final int expected = 9 - roundNumber;
			if (availableCards.size() != expected) {
				throw new IllegalStateException(String.format(
						"INVARIANT VIOLATED at chooseCard: availableCards.size=%d but expected %d " +
								"(roundNumber=%d). availableCards=%s, currentRoundPlayed=%s, alreadyPlayed=%s",
						availableCards.size(), expected, roundNumber,
						availableCards,
						game.getCurrentRound().getPlayedCardsInOrder(),
						game.getAlreadyPlayedCards()));
			}
			// Content invariant: no card in our hand should have been played already.
			// A fire here means localPlayer.cards contains stale entries — see CARD_DESYNC_BUG.md.
			final Set<Card> alreadyPlayedAtEntry = game.getAlreadyPlayedCards();
			for (Card c : availableCards) {
				if (alreadyPlayedAtEntry.contains(c)) {
					throw new IllegalStateException(String.format(
							"CONTENT INVARIANT VIOLATED at chooseCard: availableCards contains already-played card %s. " +
									"availableCards=%s, alreadyPlayed=%s, roundNumber=%d",
							c, availableCards, alreadyPlayedAtEntry, roundNumber));
				}
			}
			printCards(availableCards);

			final Set<Card> possibleCards = CardSelectionHelper.getCardsPossibleToPlay(availableCards, game);
			Card card = CardSelectionHelper.getRandomCard(possibleCards, game);

			if (possibleCards.isEmpty())
				logger.error("We have a serious problem! No possible card to play!");

			if (possibleCards.size() == 1) {
				card = Iterables.getOnlyElement(possibleCards);
				logger.info("Only one possible card to play: {}", card);
			} else { // Start searching for a good card
				if (config.isPgxRawPlayUsed() && getPgxEstimator() != null && getPgxEstimator().isLoaded()) {
					card = choosePgxRawCard(availableCards, possibleCards, game);
					logger.info("Chose card based on raw pgx policy (no search)");
				} else if (config.isMctsEnabled()) {
					try {
						if (mctsHelper == null) throw new AssertionError();
						Move move = mctsHelper.predictMove(availableCards, session, false, game.isShifted());
						card = ((CardMove) move).getPlayedCard();
						logger.info("Chose card based on MCTS, Hurra!");
					} catch (MCTSException e) {
						logger.error("{}", e);
						logger.error("Something went wrong. Had to choose random card, damn it!");
					}
				} else {
					final PlayoutSelectionPolicy playoutSelectionPolicy = config.getMctsConfig().getPlayoutSelectionPolicy();
					if (getScoreEstimator() != null) {
						// Choose the network's prediction directly, without the mcts policy enhancement
						card = getScoreEstimator().predictMove(game).getPlayedCard();
						logger.info("Chose card based only on score estimator network");
					} else if (playoutSelectionPolicy != null) {
						// Choose the result of the playout selection policy simulation directly, without the mcts policy enhancement
						card = playoutSelectionPolicy.runPlayout(game).getPlayedCard();
						logger.info("Chose card based only on {}", playoutSelectionPolicy);
					} else {
						card = CardSelectionHelper.chooseRandomCard(possibleCards);
						logger.info("Chose random card");
					}

				}
			}

			// INFO: Even if there is only one card: wait for maxThinkingTime because opponents might detect patterns otherwise
			// INFO: Commented out for machine only play. Only needed for humans
			//if (!session.getCurrentRound().isLastRound())
			//	waitUntilTimeIsUp(endingTime);

			logger.info("Total time for move: {}ms", (System.nanoTime() - startTime) / 1000000d);
			logger.info("Chose card {} out of possible cards {} out of available cards {}", card, possibleCards, availableCards);
			return card;
		} catch (Exception e) {
			logger.error("{}", e);
			logger.error("Something unexpectedly went terribly wrong! But could catch exception and chose random card now.");
			return CardSelectionHelper.getRandomCard(availableCards, game);
		}
	}

	/**
	 * Raw pgx policy play (no search). The net is trained on fully determinized
	 * observations, so it cannot be queried on the real imperfect-information state:
	 * sample the same number of determinizations MCTS would use for this round in RUNS
	 * mode, average the masked policy distribution over them, and play the argmax.
	 * Forward passes only — no tree search, no rollouts.
	 *
	 * Honored MCTSConfig knobs: cardStrengthLevel.numDeterminizationsFactor (budget),
	 * trumpConditionedDeterminization and cheating (how worlds are built; cheating uses
	 * the true hands in a single forward pass — the exact pgx-internal raw config), plus
	 * the cards estimator if enabled. Everything that parameterizes the search itself is
	 * deliberately ignored: runMode / numRuns / runsScaling / maxThinkingTime (there is
	 * no tree), MCTSHelper's TIME-mode 2x-determinization bonus and RUNS-mode /10 runs
	 * discount for leaf estimators, hardPruningEnabled (every legal card stays a
	 * candidate), and the UCB/PUCT constants. The value head is never called, and trumpf
	 * selection is untouched — it follows trumpfSelectionMethod as configured; the net's
	 * trump logits (indices 36-42) are not consulted.
	 *
	 * <p>Delegates the determinization-averaging to {@link PgxPositionAnalyzer}, which is also
	 * used by the standalone position-analysis CLI to report the full ranked distribution
	 * instead of only this argmax.
	 */
	private Card choosePgxRawCard(Set<Card> availableCards, Set<Card> possibleCards, Game game) {
		CardAnalysis analysis = new PgxPositionAnalyzer(getPgxEstimator(), config, getCardsEstimator())
				.analyzeCard(game, availableCards, possibleCards);
		logger.info("Raw pgx policy over {} determinizations: argmax {} (avg prob {})",
				analysis.getNumDeterminizations(), analysis.getBest(), analysis.getRanked().get(0).getSecond());
		return analysis.getBest();
	}

	/**
	 * Raw pgx policy trump selection (no search), the trumpf-phase analogue of
	 * {@link #choosePgxRawCard}. The net takes fully-determinized full-information observations, so
	 * the real imperfect-information state (only our own 9 cards known) can't be fed directly:
	 * sample determinizations of the 27 hidden cards, average the masked trump policy
	 * (logits 36–42) over them, and play the argmax mode. Forward passes only — no tree search,
	 * no value head. Under {@code cheating} the session already holds the true hands, so a single
	 * forward pass on the real deal suffices (diagnostic only).
	 *
	 * <p>The trump policy is trained on the same full-state trunk as the card policy (pgx
	 * {@code jass_value_net.py}), so determinization matters exactly as it does for raw card play.
	 *
	 * <p>Delegates to {@link PgxPositionAnalyzer}; see {@link #choosePgxRawCard}.
	 */
	private Mode choosePgxTrumpf(Set<Card> availableCards, GameSession session, boolean shifted) {
		TrumpAnalysis analysis = new PgxPositionAnalyzer(getPgxEstimator(), config, getCardsEstimator())
				.analyzeTrump(session, availableCards, shifted);
		logger.info("Raw pgx trump policy over {} determinizations: argmax {} (avg prob {})",
				analysis.getNumDeterminizations(), analysis.getBest(), analysis.getRanked().get(0).getSecond());
		return analysis.getBest();
	}

	private void waitUntilTimeIsUp(long endingTime) {
		while (System.currentTimeMillis() < endingTime) {
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				logger.debug("{}", e);
			}
		}
	}

	private void printCards(Set<Card> availableCards) {
		logger.info("Hi there! I am JassTheRipper and these are my cards: {} ", availableCards);
	}

	/**
	 * Only return the estimator if it is actually used. Return null otherwise.
	 *
	 * @return
	 */
	public CardsEstimator getCardsEstimator() {
		if (config.isCardsEstimatorUsed())
			return cardsEstimator;
		return null;
	}

	public void setCardsEstimator(CardsEstimator cardsEstimator) {
		this.cardsEstimator = cardsEstimator;
	}

	/**
	 * Only return the estimator if it is actually used. Return null otherwise.
	 *
	 * @return
	 */
	public ScoreEstimator getScoreEstimator() {
		if (config.isScoreEstimatorUsed())
			return scoreEstimator;
		return null;
	}

	public void setScoreEstimator(ScoreEstimator scoreEstimator) {
		this.scoreEstimator = scoreEstimator;
	}

	/**
	 * Returns the pgx estimator if it is loaded and at least one of the pgx heads is enabled.
	 * Returns {@code null} otherwise (value/policy/raw-play opt-in separately via Config).
	 */
	public PgxPolicyValueEstimator getPgxEstimator() {
		if ((config.isPgxValueUsed() || config.isPgxPolicyUsed() || config.isPgxRawPlayUsed() || config.isPgxTrumpUsed()) && pgxEstimator != null)
			return pgxEstimator;
		return null;
	}

	public void setPgxEstimator(PgxPolicyValueEstimator pgxEstimator) {
		this.pgxEstimator = pgxEstimator;
	}

	/**
	 * Returns the belief likelihood net (gen-11hc) if loaded and belief-weighted
	 * determinization is configured ({@link Config#getPgxBeliefModelPath}); null otherwise.
	 */
	public PgxPolicyValueEstimator getPgxBeliefEstimator() {
		if (config.getPgxBeliefModelPath() != null && pgxBeliefEstimator != null)
			return pgxBeliefEstimator;
		return null;
	}

	public void setPgxBeliefEstimator(PgxPolicyValueEstimator pgxBeliefEstimator) {
		this.pgxBeliefEstimator = pgxBeliefEstimator;
	}

	@Override
	public void shutDown() {
		if (this.mctsHelper != null)
			this.mctsHelper.shutDown();
	}

	public void setConfig(Config config) {
		this.config = config;
		// Because config is used in MCTSHelper, we have to restart it.
		if (this.mctsHelper != null)
			this.mctsHelper.shutDown();
		this.mctsHelper = new MCTSHelper(config.getMctsConfig());
		if (config.isCardsEstimatorUsed())
			cardsEstimator.setTrainable(config.isCardsEstimatorTrainable());
		if (config.isScoreEstimatorUsed())
			scoreEstimator.setTrainable(config.isScoreEstimatorTrainable());
	}

	public Config getConfig() {
		return config;
	}

	@Override
	public String toString() {
		return "JassTheRipperJassStrategy{" +
				"config=" + config + "}";
	}
}
