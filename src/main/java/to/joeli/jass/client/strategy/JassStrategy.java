package to.joeli.jass.client.strategy;

import to.joeli.jass.client.game.GameSession;
import to.joeli.jass.client.game.Move;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.mode.Mode;

import java.util.Set;

public interface JassStrategy {
    Mode chooseTrumpf(Set<Card> availableCards, GameSession session, boolean shifted);
    Card chooseCard(Set<Card> availableCards, GameSession session);

    default void onSessionStarted(GameSession session) {}
    default void onGameStarted(GameSession session) {}
    default void onMoveMade(Move move) {}
    default void onGameFinished() {}
    default void onSessionFinished() {}

    /**
     * Releases resources held by the strategy (e.g. MCTS thread pools). No-op by default;
     * callers must invoke this when done with the strategy so its non-daemon threads do not
     * keep the JVM alive.
     */
    default void shutDown() {}
}
