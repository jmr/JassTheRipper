package to.joeli.jass.client.strategy.mcts;

import to.joeli.jass.client.game.Game;
import to.joeli.jass.client.strategy.helpers.CardSelectionHelper;
import to.joeli.jass.client.strategy.helpers.PerfectInformationGameSolver;
import to.joeli.jass.client.strategy.mcts.src.Board;
import to.joeli.jass.client.strategy.mcts.src.Move;
import to.joeli.jass.client.strategy.mcts.src.PlayoutSelectionPolicy;
import to.joeli.jass.client.strategy.training.networks.PgxPolicyValueEstimator;
import to.joeli.jass.game.cards.Card;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * PUCT prior backed by the pgx {@code PolicyValueNet} policy head.
 *
 * <p>When used as the {@code puctPriorPolicy} in {@code MCTSConfig}, this policy runs
 * the policy head on the current determinized board position and returns the
 * argmax card as the "heuristic best" move — compatible with
 * {@code MCTS.findChildrenPuct} which expects one recommended move (weighted by
 * {@code puctAlpha}) rather than a full distribution.
 *
 * <p>{@link #runPlayout} delegates to the heavy rule-based heuristic, since this
 * class is intended only as a {@code puctPriorPolicy} and not as the simulation policy.
 */
public class PgxPlayoutSelectionPolicy implements PlayoutSelectionPolicy {

    private final PgxPolicyValueEstimator estimator;
    private final HeavyJassPlayoutSelectionPolicy fallback = new HeavyJassPlayoutSelectionPolicy();

    public PgxPlayoutSelectionPolicy(PgxPolicyValueEstimator estimator) {
        this.estimator = estimator;
    }

    /**
     * Returns the move with the highest policy probability among the legal cards.
     *
     * <p>Falls back to the heavy heuristic if:
     * <ul>
     *   <li>the board is not a {@link JassBoard} (e.g. during trump selection),</li>
     *   <li>no game is attached (trump-selection phase),</li>
     *   <li>the legal-card set is empty.</li>
     * </ul>
     */
    @Override
    public Move getBestMove(Board board) {
        if (!(board instanceof JassBoard)) {
            return fallback.getBestMove(board);
        }
        Game game = ((JassBoard) board).getGame();
        if (game == null) {
            return fallback.getBestMove(board);   // trump-selection phase
        }

        Set<Card> playerCards = game.getCurrentPlayer().getCards();
        if (playerCards.isEmpty()) {
            return fallback.getBestMove(board);
        }

        Set<Card> legal = CardSelectionHelper.getCardsPossibleToPlay(
                EnumSet.copyOf(playerCards), game);
        if (legal.isEmpty()) {
            return fallback.getBestMove(board);
        }

        Map<Card, Float> prior = estimator.predictPriorOverLegal(game, legal);
        Card best = prior.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(legal.iterator().next());

        return new CardMove(game.getCurrentPlayer(), best);
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
