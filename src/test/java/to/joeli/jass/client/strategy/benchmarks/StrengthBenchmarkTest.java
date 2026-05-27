package to.joeli.jass.client.strategy.benchmarks;

import org.junit.Test;
import org.junit.experimental.categories.Category;
import to.joeli.jass.client.strategy.config.Config;
import to.joeli.jass.client.strategy.config.MCTSConfig;
import to.joeli.jass.client.strategy.config.StrengthLevel;
import to.joeli.jass.client.strategy.training.Arena;

/**
 * Measures MCTS strength vs pure rule-based heuristic to verify playout optimizations
 * translate into stronger play.
 *
 * Run:
 *   ./gradlew test --tests "to.joeli.jass.client.strategy.benchmarks.StrengthBenchmarkTest.mctsExtremeVsRuleBased"
 */
@Category(SlowBenchmark.class)
public class StrengthBenchmarkTest {

    private static final int NUM_PAIRS = 15; // 30 games total (15 pairs for fairness)
    private static final int NUM_GAMES = NUM_PAIRS * 2;

    private final Arena arena = new Arena(Arena.IMPROVEMENT_THRESHOLD_PERCENTAGE, Arena.SEED, false);

    /**
     * MCTS at EXTREME strength (2s budget) vs pure rule-based heuristic.
     * Measures whether the 7x playout speedup translates to game strength.
     * Previous baseline (pre-optimization): ~90-96%.
     */
    @Test
    public void mctsExtremeVsRuleBased() {
        Config[] configs = {
                new Config(true, false, false),   // MCTS card play
                new Config(false, false, false),  // Rule-based only
        };
        configs[0].setMctsConfig(new MCTSConfig(StrengthLevel.EXTREME));

        final double performance = arena.runMatchWithConfigs(configs, NUM_GAMES);

        Arena.resultLogger.info("MCTS EXTREME vs RULE_BASED: {}% ({} games)", String.format("%.2f", performance), NUM_GAMES);
        System.out.printf("MCTS EXTREME vs RULE_BASED: %.2f%% (%d games)%n", performance, NUM_GAMES);
    }
}
