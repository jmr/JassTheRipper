package to.joeli.jass.client.strategy.training;

import org.junit.Test;
import to.joeli.jass.client.strategy.config.Config;
import to.joeli.jass.client.strategy.config.MCTSConfig;
import to.joeli.jass.client.strategy.config.StrengthLevel;

import static to.joeli.jass.client.strategy.training.Arena.IMPROVEMENT_THRESHOLD_PERCENTAGE;

/**
 * Benchmarks UCB exploration constant values using the swapped-cards Arena framework.
 *
 * Each game pair uses duplicate-Jass (hands swapped between teams) for fairness.
 * Results are logged to the "Result" logger.
 *
 * To run: remove @Ignore, then:
 *   ./gradlew test --tests "to.joeli.jass.client.strategy.training.UCBBenchmarkTest.explorationConstant_sqrt2_vs_hoeffding"
 * Expected wall time: ~12 minutes (10 games × 36 moves × 2 s/move).
 */
public class UCBBenchmarkTest {

    @Test
    public void explorationConstant_sqrt2_vs_hoeffding() {
        Arena arena = new Arena(IMPROVEMENT_THRESHOLD_PERCENTAGE, Arena.SEED, false);

        MCTSConfig sqrt2Config = new MCTSConfig(Math.sqrt(2.0));
        sqrt2Config.setCardStrengthLevel(StrengthLevel.EXTREME);

        MCTSConfig c1000Config = new MCTSConfig(1000.0);
        c1000Config.setCardStrengthLevel(StrengthLevel.EXTREME);

        MCTSConfig c10000Config = new MCTSConfig(10000.0);
        c10000Config.setCardStrengthLevel(StrengthLevel.EXTREME);

        double result1k = arena.runMatchWithConfigs(new Config[]{new Config(c1000Config), new Config(sqrt2Config)}, 10);
        Arena.resultLogger.info("c=1000 scored {}% of sqrt(2) points (>100 means c=1000 wins)", result1k);

        double result10k = arena.runMatchWithConfigs(new Config[]{new Config(c10000Config), new Config(sqrt2Config)}, 10);
        Arena.resultLogger.info("c=10000 scored {}% of sqrt(2) points (>100 means c=10000 wins)", result10k);
    }
}
