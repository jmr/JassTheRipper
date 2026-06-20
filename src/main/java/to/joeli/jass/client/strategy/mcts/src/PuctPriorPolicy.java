package to.joeli.jass.client.strategy.mcts.src;

import java.util.Map;

/**
 * A PUCT prior that supplies a full probability distribution {@code P(s,a)} over the legal
 * moves at a board position.
 *
 * <p>This is the AlphaZero-style prior: {@link MCTS#findChildrenPuct} weights each child's
 * exploration term by its prior probability. It contrasts with
 * {@link PlayoutSelectionPolicy#getBestMove}, which collapses the policy to a single argmax
 * "tip" — that loses the calibrated distribution shape, which is exactly where the pgx
 * policy network's generation-to-generation improvements live.
 */
public interface PuctPriorPolicy {

    /**
     * @param board the (determinized) board at the node whose children are being ranked
     * @return prior probabilities keyed by legal {@link Move}; an empty map if unavailable
     *         (the caller then falls back to a uniform prior)
     */
    Map<Move, Double> priors(Board board);
}
