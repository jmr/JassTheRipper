package to.joeli.jass.client.strategy.helpers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import to.joeli.jass.client.game.Game;
import to.joeli.jass.client.game.GameSession;
import to.joeli.jass.client.game.Move;
import to.joeli.jass.client.game.Player;
import to.joeli.jass.client.game.Team;
import to.joeli.jass.client.strategy.RandomJassStrategy;
import to.joeli.jass.client.strategy.training.networks.CardsEstimator;
import to.joeli.jass.game.cards.Card;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Belief-weighted determinization: the particle filter of pgx
 * {@code jass_belief.py} (pgx log 2026-07-17), ported to the JTR engine for the
 * external check vs POWERFUL.
 *
 * <p>Per card decision:
 * <ol>
 *   <li>sample N void-consistent candidate worlds for the mover (the standard
 *       {@link CardKnowledgeBase} determinization — the uniform proposal);
 *   <li>weight each world w by the hc likelihood net's probability of the OTHER
 *       three players' observed card plays so far,
 *       {@code log L(w) = Σ_t log P_hc(move_t | state_t under w)}, the policy
 *       evaluated from the mover-at-t's seat;
 *   <li>the MCTS root determinizations are then drawn with replacement ∝ the
 *       softmax-normalized weights ({@link to.joeli.jass.client.strategy.mcts.JassBoard#setBeliefWorlds}).
 *       Duplicated root worlds ARE the belief mass — no deduplication, and no
 *       true-world injection (that was the pgx probe's measurement device only).
 * </ol>
 *
 * <p>Past states under a candidate world need no per-world game replay: hands at
 * step t are the world's hands NOW plus the cards each player publicly played in
 * [t, now). The public trajectory itself (tricks, scores, leader, turn order) is
 * replayed ONCE per decision on a shadow session, GameNotation-style —
 * {@code Round.makeMove} only checks turn order, not hand membership, so the
 * shadow players' hands can be overwritten per world at each scored step before
 * encoding.
 *
 * <p>Fair by construction: the filter reads only the mover's own hand and public
 * fields/diffs — everything is observable from outside, so the pgx
 * oracle-contamination rule (raw TRUE-state arenas, log 2026-07-15) does not
 * bite here.
 *
 * <p>Divergence from the pgx implementation: the two trump-phase decisions
 * (shift / declaration) are not scored — JTR's {@link Game} move record carries
 * card plays only. The pgx probe measured trump-only likelihood at q̄ 0.039
 * (near-uninformative), and the round-0 shift information is partially covered
 * by {@code trumpConditionedDeterminization}'s rejection sampling instead.
 */
public final class PgxBeliefFilter {

	/** N candidate worlds per decision (the pgx probe and gate used 32). */
	public static final int DEFAULT_NUM_PARTICLES = 32;

	/**
	 * Log-likelihood contribution of an observed move that is ILLEGAL under the
	 * candidate world (matches pgx's masked-logit fill of -1e9): the world is
	 * inconsistent with the observed legality and its weight collapses to ~0.
	 */
	private static final double LOG_ILLEGAL = -1e9;

	/** Batched policy forward pass of the hc likelihood net, injectable for tests. */
	public interface BatchLogitsFn {
		/** @return {@code [rows.length][43]} raw (unmasked) action logits */
		float[][] apply(PgxFeatureEncoder.Features[] rows);
	}

	/** The filter's output: N seat-indexed candidate hand assignments and their weights. */
	public static final class Belief {
		/** {@code worlds.get(n).get(seatId)} = seat's candidate hand at the current decision. */
		public final List<List<Set<Card>>> worlds;
		/** Normalized weights, same order as {@link #worlds}; sums to 1. */
		public final double[] weights;

		Belief(List<List<Set<Card>>> worlds, double[] weights) {
			this.worlds = worlds;
			this.weights = weights;
		}

		/** Effective sample size 1/Σw² in [1, N] — the pgx gate saw mid-game ESS ≈ 2.5. */
		public double effectiveSampleSize() {
			double sumSq = 0.0;
			for (double w : weights)
				sumSq += w * w;
			return 1.0 / sumSq;
		}
	}

	private final BatchLogitsFn hcLogitsFn;
	private final int numParticles;
	private final double mixUniform;

	public static final Logger logger = LoggerFactory.getLogger(PgxBeliefFilter.class);

	/**
	 * @param hcLogitsFn  batched forward pass of the hc likelihood net (gen-11hc),
	 *                    e.g. {@code hcEstimator::forwardLogitsBatch}
	 * @param numParticles N sampled candidate worlds per decision
	 * @param mixUniform  λ share of uniform blended into the weights — the guard
	 *                    against a degenerate/misspecified likelihood (pgx gate: 0)
	 */
	public PgxBeliefFilter(BatchLogitsFn hcLogitsFn, int numParticles, double mixUniform) {
		if (numParticles < 1)
			throw new IllegalArgumentException("numParticles must be >= 1: " + numParticles);
		if (mixUniform < 0.0 || mixUniform > 1.0)
			throw new IllegalArgumentException("mixUniform must be in [0,1]: " + mixUniform);
		this.hcLogitsFn = hcLogitsFn;
		this.numParticles = numParticles;
		this.mixUniform = mixUniform;
	}

	/**
	 * One particle-filter pass for the current player of {@code game}.
	 *
	 * <p>At the first card decision of a game (no opponent moves recorded yet) the
	 * weights are exactly uniform.
	 *
	 * @param game             the mover's (imperfect-information) view of the game
	 * @param availableCards   the mover's own remaining hand
	 * @param cardsEstimator   optional proposal enhancer for the world sampler (null = heuristic voids only)
	 * @param trumpConditioned round-0 shift/no-shift rejection sampling for the proposal
	 */
	public Belief computeBelief(Game game, Set<Card> availableCards,
								CardsEstimator cardsEstimator, boolean trumpConditioned) {
		List<List<Set<Card>>> worlds = sampleWorlds(game, availableCards, cardsEstimator, trumpConditioned);
		double[] logLikelihoods = logLikelihoods(game, worlds);
		double[] weights = normalize(logLikelihoods);
		return new Belief(worlds, weights);
	}

	// ── Step 1: the uniform proposal ─────────────────────────────────────────

	private List<List<Set<Card>>> sampleWorlds(Game game, Set<Card> availableCards,
											   CardsEstimator cardsEstimator, boolean trumpConditioned) {
		Set<Card> alreadyPlayed = game.getAlreadyPlayedCards();
		List<List<Set<Card>>> worlds = new ArrayList<>(numParticles);
		for (int n = 0; n < numParticles; n++) {
			Game copy = new Game(game);
			CardKnowledgeBase.sampleCardDeterminizationToPlayers(copy, availableCards, cardsEstimator, trumpConditioned);
			List<Set<Card>> hands = seatIndexedHands(copy);
			assertWorldIsConsistent(hands, alreadyPlayed);
			worlds.add(hands);
		}
		return worlds;
	}

	private static List<Set<Card>> seatIndexedHands(Game game) {
		List<Set<Card>> hands = new ArrayList<>(java.util.Collections.nCopies(4, (Set<Card>) null));
		for (Player player : game.getPlayers())
			hands.set(player.getSeatId(), EnumSet.copyOf(player.getCards()));
		return hands;
	}

	/** Candidate hands plus the publicly played cards must partition the full deck. */
	private static void assertWorldIsConsistent(List<Set<Card>> hands, Set<Card> alreadyPlayed) {
		Set<Card> seen = EnumSet.copyOf(alreadyPlayed);
		for (Set<Card> hand : hands) {
			if (hand == null)
				throw new IllegalStateException("Belief world is missing a seat's hand");
			for (Card card : hand)
				if (!seen.add(card))
					throw new IllegalStateException(
							"Belief world is inconsistent: " + card + " is held twice or already played");
		}
		if (seen.size() != Card.values().length)
			throw new IllegalStateException(
					"Belief world is inconsistent: hands + played cards cover only " + seen.size() + " of 36 cards");
	}

	// ── Step 2: the hc likelihood over the public record ─────────────────────

	/**
	 * {@code log L(w) = Σ_t log P_hc(move_t | state_t under w)} over all recorded
	 * card plays whose mover is not the believer.
	 * Package-private so tests can score hand-crafted worlds directly.
	 */
	double[] logLikelihoods(Game game, List<List<Set<Card>>> worlds) {
		int believerSeat = game.getCurrentPlayer().getSeatId();
		List<Move> record = game.getAlreadyPlayedMovesInOrder();
		double[] logL = new double[worlds.size()];
		if (record.isEmpty())
			return logL;

		// Per-seat cards still to be replayed from the current step to now — at step t the
		// mover's hand under world w is worlds[w][seat] ∪ suffix[seat].
		List<Set<Card>> suffix = new ArrayList<>(4);
		for (int seat = 0; seat < 4; seat++)
			suffix.add(EnumSet.noneOf(Card.class));
		for (Move move : record)
			suffix.get(move.getPlayer().getSeatId()).add(move.getPlayedCard());

		// The round-0 forehand is the player of the first recorded move: the forehand
		// always leads trick 1 (shifted only changes who declared, never who leads).
		// Deriving it from a PlayingOrder would be wrong: getPlayersInInitialOrder()
		// returns the session seating list, whose index 0 is NOT the leader once the
		// session's starting player has rotated between games.
		ReplaySession replay = new ReplaySession(game, record.get(0).getPlayer().getSeatId());
		for (Move move : record) {
			int moverSeat = move.getPlayer().getSeatId();
			Card played = move.getPlayedCard();
			if (replay.currentPlayer().getSeatId() != moverSeat)
				throw new IllegalStateException(String.format(
						"Belief replay desynced: recorded mover seat %d but replay expects seat %d",
						moverSeat, replay.currentPlayer().getSeatId()));

			if (moverSeat != believerSeat)
				scoreStep(replay, worlds, suffix, moverSeat, played, logL);

			suffix.get(moverSeat).remove(played);
			replay.makeMove(played);
		}
		return logL;
	}

	/** Adds each world's log-probability of the observed {@code played} card to {@code logL}. */
	private void scoreStep(ReplaySession replay, List<List<Set<Card>>> worlds,
						   List<Set<Card>> suffix, int moverSeat, Card played, double[] logL) {
		int actionIdx = PgxFeatureEncoder.pgxIndex(played);
		PgxFeatureEncoder.Features[] rows = new PgxFeatureEncoder.Features[worlds.size()];
		List<Set<Card>> legalPerWorld = new ArrayList<>(worlds.size());

		for (int n = 0; n < worlds.size(); n++) {
			Set<Card> moverHand = replay.setHands(worlds.get(n), suffix);
			if (!moverHand.contains(played))
				throw new IllegalStateException(String.format(
						"Belief reconstruction broken: seat %d's reconstructed hand %s does not hold the played %s",
						moverSeat, moverHand, played));
			rows[n] = PgxFeatureEncoder.encode(replay.game());
			legalPerWorld.add(CardSelectionHelper.getCardsPossibleToPlay(moverHand, replay.game()));
		}

		float[][] logits = hcLogitsFn.apply(rows);
		for (int n = 0; n < worlds.size(); n++) {
			Set<Card> legal = legalPerWorld.get(n);
			if (!legal.contains(played)) {
				// The observed move is illegal under this world: the world contradicts the
				// public record (e.g. it gives the mover a card of the led suit they did not
				// follow with) — its weight collapses to ~0.
				logL[n] += LOG_ILLEGAL;
				continue;
			}
			// log-softmax over the legal card logits only, as in pgx (masked fill -1e9).
			double max = Double.NEGATIVE_INFINITY;
			for (Card card : legal)
				max = Math.max(max, logits[n][PgxFeatureEncoder.pgxIndex(card)]);
			double sumExp = 0.0;
			for (Card card : legal)
				sumExp += Math.exp(logits[n][PgxFeatureEncoder.pgxIndex(card)] - max);
			logL[n] += logits[n][actionIdx] - max - Math.log(sumExp);
		}
	}

	// ── Step 3: softmax weights ──────────────────────────────────────────────

	private double[] normalize(double[] logL) {
		int n = logL.length;
		double max = Double.NEGATIVE_INFINITY;
		for (double l : logL)
			max = Math.max(max, l);
		double[] weights = new double[n];
		double sum = 0.0;
		for (int i = 0; i < n; i++) {
			weights[i] = Math.exp(logL[i] - max);
			sum += weights[i];
		}
		for (int i = 0; i < n; i++) {
			weights[i] /= sum;
			if (mixUniform > 0.0)
				weights[i] = (1.0 - mixUniform) * weights[i] + mixUniform / n;
		}
		return weights;
	}

	// ── The shadow replay of the public trajectory ───────────────────────────

	/**
	 * Replays the public record on a fresh session, GameNotation-style: four fresh
	 * players seated so the round-0 forehand leads, {@code startNewGame} for the
	 * recorded trump, {@code makeMove} per card, {@code startNextRound} per
	 * completed trick. The shadow players' hands start empty (never invented) and
	 * are overwritten per candidate world at each scored step.
	 */
	private static final class ReplaySession {
		private final GameSession session;
		private final List<Player> playersBySeat;

		ReplaySession(Game game, int foreSeat) {
			List<Player> players = new ArrayList<>(4);
			// RandomJassStrategy is a stateless placeholder: the replay never asks the shadow
			// players for decisions, and a JassTheRipperJassStrategy here would leak an MCTS
			// thread pool per player per decision (its constructor starts one).
			for (int seat = 0; seat < 4; seat++)
				players.add(new Player(String.valueOf(seat), "Belief" + seat, seat,
						EnumSet.noneOf(Card.class), new RandomJassStrategy()));
			List<Team> teams = List.of(
					new Team("Team0", List.of(players.get(0), players.get(2))),
					new Team("Team1", List.of(players.get(1), players.get(3))));

			// Seat the round-0 forehand first: PlayingOrder starts at list index 0.
			List<Player> order = new ArrayList<>(4);
			for (int i = 0; i < 4; i++)
				order.add(players.get((foreSeat + i) & 3));

			this.session = new GameSession(teams, order);
			this.playersBySeat = players;
			session.startNewGame(game.getMode(), game.isShifted());
		}

		Game game() {
			return session.getCurrentGame();
		}

		Player currentPlayer() {
			return session.getCurrentPlayer();
		}

		void makeMove(Card card) {
			session.makeMove(new Move(session.getCurrentPlayer(), card));
			if (session.getCurrentRound().roundFinished())
				session.startNextRound();
		}

		/**
		 * Overwrites all four shadow hands with {@code world's hands now ∪ the cards
		 * still to be replayed} and returns the current (to-move) player's hand.
		 */
		Set<Card> setHands(List<Set<Card>> world, List<Set<Card>> suffix) {
			for (int seat = 0; seat < 4; seat++) {
				Set<Card> hand = EnumSet.copyOf(world.get(seat));
				hand.addAll(suffix.get(seat));
				playersBySeat.get(seat).setCards(hand);
			}
			return EnumSet.copyOf(currentPlayer().getCards());
		}
	}
}
