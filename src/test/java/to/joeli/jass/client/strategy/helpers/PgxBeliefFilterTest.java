package to.joeli.jass.client.strategy.helpers;

import org.junit.Test;
import to.joeli.jass.client.game.Game;
import to.joeli.jass.client.game.GameSession;
import to.joeli.jass.client.game.Move;
import to.joeli.jass.client.game.Player;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.mode.Mode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static java.util.Arrays.asList;
import static org.junit.Assert.*;
import static to.joeli.jass.game.cards.Card.*;

/**
 * Unit tests for {@link PgxBeliefFilter}.
 *
 * The scoring core is exercised with a constant-zero-logits likelihood (the pgx probe's
 * "legality channel"): every legal card is equally likely, so a world's log-likelihood is
 * exactly {@code Σ -log |legal moves|} over the scored steps — analytically checkable — and
 * a world under which an observed move was ILLEGAL collapses to ~zero weight.
 */
public class PgxBeliefFilterTest {

	/** One suit per seat: P0 all diamonds, P1 all hearts, P2 all spades, P3 all clubs. */
	private static final List<Set<Card>> ONE_SUIT_PER_SEAT = asList(
			EnumSet.of(DIAMOND_SIX, DIAMOND_SEVEN, DIAMOND_EIGHT, DIAMOND_NINE, DIAMOND_TEN, DIAMOND_JACK, DIAMOND_QUEEN, DIAMOND_KING, DIAMOND_ACE),
			EnumSet.of(HEART_SIX, HEART_SEVEN, HEART_EIGHT, HEART_NINE, HEART_TEN, HEART_JACK, HEART_QUEEN, HEART_KING, HEART_ACE),
			EnumSet.of(SPADE_SIX, SPADE_SEVEN, SPADE_EIGHT, SPADE_NINE, SPADE_TEN, SPADE_JACK, SPADE_QUEEN, SPADE_KING, SPADE_ACE),
			EnumSet.of(CLUB_SIX, CLUB_SEVEN, CLUB_EIGHT, CLUB_NINE, CLUB_TEN, CLUB_JACK, CLUB_QUEEN, CLUB_KING, CLUB_ACE));

	private static final PgxBeliefFilter.BatchLogitsFn ZERO_LOGITS = rows -> new float[rows.length][43];

	private static PgxBeliefFilter filter(int particles) {
		return new PgxBeliefFilter(ZERO_LOGITS, particles, 0.0);
	}

	private static Set<Card> minus(Set<Card> cards, Card card) {
		Set<Card> result = EnumSet.copyOf(cards);
		result.remove(card);
		return result;
	}

	// ── Scoring core on hand-crafted worlds ──────────────────────────────────

	/**
	 * Top-down game, P0 led ♦6, P1 (void in ♦) discarded ♥6, P2 (void) discarded ♠6;
	 * P3 to move is the believer. All three recorded moves belong to opponents.
	 */
	private Game threeMovesPlayedGame() {
		GameSession session = GameSessionBuilder.newSession(ONE_SUIT_PER_SEAT)
				.withStartedGame(Mode.topDown())
				.withCardsPlayed(DIAMOND_SIX, HEART_SIX, SPADE_SIX)
				.createGameSession();
		Game game = session.getCurrentGame();
		assertEquals(3, game.getCurrentPlayer().getSeatId());
		return game;
	}

	/** The hands each seat actually holds NOW (after the three plays). */
	private List<Set<Card>> trueWorld() {
		return asList(
				minus(ONE_SUIT_PER_SEAT.get(0), DIAMOND_SIX),
				minus(ONE_SUIT_PER_SEAT.get(1), HEART_SIX),
				minus(ONE_SUIT_PER_SEAT.get(2), SPADE_SIX),
				EnumSet.copyOf(ONE_SUIT_PER_SEAT.get(3)));
	}

	@Test
	public void legalityOnlyLikelihoodIsSumOfLogLegalMoveCounts() {
		Game game = threeMovesPlayedGame();

		double[] logL = filter(1).logLikelihoods(game, List.of(trueWorld()));

		// Each of the three scored steps had 9 legal moves (the leader plays anything;
		// the two void followers may discard any of their 9 cards).
		assertEquals(-3.0 * Math.log(9.0), logL[0], 1e-6);
	}

	@Test
	public void worldMakingAnObservedMoveIllegalCollapsesToZeroWeight() {
		Game game = threeMovesPlayedGame();

		// World B swaps ♦7 (P0) and ♥7 (P1): under B, P1 held ♦7 when ♦ was led, so
		// discarding ♥6 violated following suit — the world contradicts the record.
		List<Set<Card>> worldB = new ArrayList<>(trueWorld());
		Set<Card> hand0 = minus(worldB.get(0), DIAMOND_SEVEN);
		hand0.add(HEART_SEVEN);
		Set<Card> hand1 = minus(worldB.get(1), HEART_SEVEN);
		hand1.add(DIAMOND_SEVEN);
		worldB.set(0, hand0);
		worldB.set(1, hand1);

		PgxBeliefFilter beliefFilter = filter(2);
		double[] logL = beliefFilter.logLikelihoods(game, asList(trueWorld(), worldB));

		assertTrue("illegal-move world must be vastly less likely: " + logL[1] + " vs " + logL[0],
				logL[1] < logL[0] - 1e8);
	}

	/**
	 * Regression for the game-#2 desync of the first arena run: from the session's second
	 * game on, the starting player rotates, so the session seating list no longer begins
	 * with the forehand — the replay must derive the round-0 leader from the first
	 * recorded move, not from {@code getPlayersInInitialOrder().get(0)}.
	 */
	@Test
	public void replayHandlesRotatedForehandInLaterSessionGames() {
		GameSession session = GameSessionBuilder.newSession(ONE_SUIT_PER_SEAT)
				.withStartedGame(Mode.topDown())
				.createGameSession();
		session.startNewGame(Mode.topDown(), false);
		Game game = session.getCurrentGame();
		assertEquals("second game of the session must be led by seat 1",
				1, game.getCurrentPlayer().getSeatId());

		// P1 leads ♥6; P2 and P3, void in hearts, discard ♠6 and ♣6; P0 is the believer.
		for (Card card : new Card[]{HEART_SIX, SPADE_SIX, CLUB_SIX}) {
			Player player = session.getCurrentPlayer();
			Move move = new Move(player, card);
			session.makeMove(move);
			player.onMoveMade(move);
		}
		assertEquals(0, game.getCurrentPlayer().getSeatId());

		List<Set<Card>> world = asList(
				EnumSet.copyOf(ONE_SUIT_PER_SEAT.get(0)),
				minus(ONE_SUIT_PER_SEAT.get(1), HEART_SIX),
				minus(ONE_SUIT_PER_SEAT.get(2), SPADE_SIX),
				minus(ONE_SUIT_PER_SEAT.get(3), CLUB_SIX));
		double[] logL = filter(1).logLikelihoods(game, List.of(world));

		// Three scored opponent steps, 9 legal moves each — same shape as the first-game test.
		assertEquals(-3.0 * Math.log(9.0), logL[0], 1e-6);
	}

	// ── End-to-end computeBelief ─────────────────────────────────────────────

	@Test
	public void beliefIsUniformAtTheFirstDecisionOfAGame() {
		Game game = GameSessionBuilder.newSession(ONE_SUIT_PER_SEAT)
				.withStartedGame(Mode.topDown())
				.createGameSession()
				.getCurrentGame();
		Set<Card> availableCards = EnumSet.copyOf(game.getCurrentPlayer().getCards());

		PgxBeliefFilter.Belief belief = filter(8).computeBelief(game, availableCards, null, false);

		assertEquals(8, belief.worlds.size());
		for (double weight : belief.weights)
			assertEquals(1.0 / 8, weight, 1e-9);
		assertEquals(8.0, belief.effectiveSampleSize(), 1e-6);
		// Every sampled world keeps the believer's own hand fixed.
		for (List<Set<Card>> world : belief.worlds)
			assertEquals(availableCards, world.get(game.getCurrentPlayer().getSeatId()));
	}

	@Test
	public void midGameBeliefWeightsAreNormalizedAndWorldsPartitionTheDeck() {
		Game game = threeMovesPlayedGame();
		Set<Card> availableCards = EnumSet.copyOf(game.getCurrentPlayer().getCards());

		PgxBeliefFilter.Belief belief = filter(16).computeBelief(game, availableCards, null, false);

		double sum = 0.0;
		for (double weight : belief.weights) {
			assertTrue(weight >= 0.0);
			sum += weight;
		}
		assertEquals(1.0, sum, 1e-9);
		Set<Card> played = game.getAlreadyPlayedCards();
		for (List<Set<Card>> world : belief.worlds) {
			Set<Card> seen = EnumSet.copyOf(played);
			for (Set<Card> hand : world)
				for (Card card : hand)
					assertTrue("card held twice or already played: " + card, seen.add(card));
			assertEquals(36, seen.size());
		}
	}

	@Test
	public void mixUniformFloorsTheWeights() {
		Game game = threeMovesPlayedGame();
		Set<Card> availableCards = EnumSet.copyOf(game.getCurrentPlayer().getCards());

		PgxBeliefFilter.Belief belief = new PgxBeliefFilter(ZERO_LOGITS, 4, 1.0)
				.computeBelief(game, availableCards, null, false);

		for (double weight : belief.weights)
			assertEquals(0.25, weight, 1e-9);
	}
}
