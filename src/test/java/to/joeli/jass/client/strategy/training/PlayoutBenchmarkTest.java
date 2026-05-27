package to.joeli.jass.client.strategy.training;

import org.junit.Test;
import org.junit.experimental.categories.Category;
import to.joeli.jass.client.strategy.benchmarks.SlowBenchmark;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import to.joeli.jass.client.game.Game;
import to.joeli.jass.client.game.GameSession;
import to.joeli.jass.client.game.Player;
import to.joeli.jass.client.strategy.helpers.CardSelectionHelper;
import to.joeli.jass.client.strategy.helpers.GameSessionBuilder;
import to.joeli.jass.client.strategy.helpers.TrumpfSelectionHelper;
import to.joeli.jass.client.strategy.mcts.CardMove;
import to.joeli.jass.client.strategy.mcts.JassBoard;
import to.joeli.jass.client.strategy.mcts.src.Board;
import to.joeli.jass.client.strategy.mcts.src.CallLocation;
import to.joeli.jass.client.strategy.mcts.src.Move;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.cards.CardSet;
import to.joeli.jass.game.mode.Mode;

import java.util.*;
import java.util.random.RandomGenerator;

/**
 * Measures single-threaded random-playout throughput (full game from deal to finish).
 * Compare against JAX baseline of ~8.5k rollouts/sec.
 *
 * Run:
 *   ./gradlew test --tests "to.joeli.jass.client.strategy.training.PlayoutBenchmarkTest.randomPlayoutThroughput"
 */
@Category(SlowBenchmark.class)
public class PlayoutBenchmarkTest {

    private static final Logger resultLogger = LoggerFactory.getLogger("Result");

    @Test
    public void randomPlayoutThroughput() {
        GameSession session = GameSessionBuilder.newSession().createGameSession();
        List<Card> cards = new ArrayList<>(Arrays.asList(Card.values()));
        Random shuffleRng = new Random(42);
        RandomGenerator rng = new SplittableRandom(42);
        Mode mode = Mode.trump(to.joeli.jass.game.cards.Color.HEARTS);

        // Warm up JIT
        int warmupRollouts = 500;
        for (int i = 0; i < warmupRollouts; i++) {
            Collections.shuffle(cards, shuffleRng);
            session.dealCards(cards);
            runRandomPlayout(session, mode, rng);
        }

        // Measure for 60 seconds
        long durationMs = 60_000;
        long start = System.currentTimeMillis();
        long end = start + durationMs;
        int count = 0;
        while (System.currentTimeMillis() < end) {
            Collections.shuffle(cards, shuffleRng);
            session.dealCards(cards);
            runRandomPlayout(session, mode, rng);
            count++;
        }

        double rolloutsSec = count / (durationMs / 1000.0);
        resultLogger.info("Random playout throughput (single-threaded): {} rollouts/sec ({} rollouts in {}s)",
                (int) rolloutsSec, count, durationMs / 1000);
        System.out.printf("Rollout throughput: %.0f/sec%n", rolloutsSec);
    }

    private void runRandomPlayout(GameSession session, Mode mode, RandomGenerator rng) {
        session.startNewGame(mode, false);
        Game game = session.getCurrentGame();
        while (!game.gameFinished()) {
            while (!game.getCurrentRound().roundFinished()) {
                Player p = game.getCurrentPlayer();
                long possibleBits = CardSelectionHelper.getCardsPossibleToPlayBits(p.getCardBits(), game);
                Card card = possibleBits == 0L
                        ? p.getCards().iterator().next()
                        : CardSet.pickRandom(possibleBits, rng);
                CardMove move = new CardMove(p, card);
                game.makeMove(move);
                p.onMoveMade(move);
            }
            session.startNextRound();
        }
    }
}
