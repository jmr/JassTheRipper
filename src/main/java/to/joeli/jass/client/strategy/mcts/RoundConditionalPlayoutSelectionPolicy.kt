package to.joeli.jass.client.strategy.mcts

import to.joeli.jass.client.game.Game
import to.joeli.jass.client.strategy.helpers.CardSelectionHelper
import to.joeli.jass.client.strategy.mcts.src.Board
import to.joeli.jass.client.strategy.mcts.src.Move
import to.joeli.jass.client.strategy.mcts.src.PlayoutSelectionPolicy
import java.util.EnumSet

/**
 * Applies [earlyPolicy] while the playout is in tricks `0..numEarlyRounds-1`, then falls back to a
 * uniform-random legal move (matching MCTS's null-policy default). Lets us test whether richer
 * rollouts disproportionately help the early game where the playout horizon is longest.
 */
class RoundConditionalPlayoutSelectionPolicy(
        private val earlyPolicy: PlayoutSelectionPolicy,
        private val numEarlyRounds: Int
) : PlayoutSelectionPolicy {

    override fun getBestMove(board: Board): Move {
        return board.getBestMove(this)
    }

    override fun runPlayout(game: Game): CardMove {
        if (game.currentRound.roundNumber < numEarlyRounds)
            return earlyPolicy.runPlayout(game)
        val possible = CardSelectionHelper.getCardsPossibleToPlay(EnumSet.copyOf(game.currentPlayer.cards), game)
        return CardMove(game.currentPlayer, CardSelectionHelper.chooseRandomCard(possible))
    }

    override fun toString(): String = "$earlyPolicy for rounds 0..${numEarlyRounds - 1}, random after"
}
