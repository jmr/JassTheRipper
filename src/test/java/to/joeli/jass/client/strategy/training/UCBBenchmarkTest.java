package to.joeli.jass.client.strategy.training;

import org.junit.Test;
import org.junit.experimental.categories.Category;
import to.joeli.jass.client.strategy.benchmarks.SlowBenchmark;
import to.joeli.jass.client.strategy.config.Config;
import to.joeli.jass.client.strategy.config.MCTSConfig;
import to.joeli.jass.client.strategy.config.StrengthLevel;
import to.joeli.jass.client.strategy.config.TrumpfSelectionMethod;

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
@Category(SlowBenchmark.class)
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

    @Test
    public void mctsBeforeShiftVsRuleBased() {
        Arena arena = new Arena(IMPROVEMENT_THRESHOLD_PERCENTAGE, Arena.SEED, false);

        MCTSConfig mctsConfig = new MCTSConfig();
        mctsConfig.setCardStrengthLevel(StrengthLevel.EXTREME);
        mctsConfig.setTrumpfStrengthLevel(StrengthLevel.IRONMAN);
        mctsConfig.setTrumpfNumCandidates(2);

        MCTSConfig ruleConfig = new MCTSConfig();
        ruleConfig.setCardStrengthLevel(StrengthLevel.EXTREME);

        Config[] configs = {
                new Config(mctsConfig, TrumpfSelectionMethod.MCTS_BEFORE_SHIFT),
                new Config(ruleConfig)
        };

        double result = arena.runMatchWithConfigs(configs, 60);
        Arena.resultLogger.info("MCTS_BEFORE_SHIFT scored {}% of RULE_BASED points (>100 means MCTS_BEFORE_SHIFT wins)", result);
    }

    @Test
    public void halfHeuristicVsRuleBased() {
        Arena arena = new Arena(IMPROVEMENT_THRESHOLD_PERCENTAGE, Arena.SEED, false);

        // Team A: MCTS_ON_SHIFT — rule-based initial, MCTS IRONMAN (10s) when geschoben
        MCTSConfig halfConfig = new MCTSConfig();
        halfConfig.setCardStrengthLevel(StrengthLevel.EXTREME);
        halfConfig.setTrumpfStrengthLevel(StrengthLevel.IRONMAN);

        // Team B: pure rule-based trump selection
        MCTSConfig ruleConfig = new MCTSConfig();
        ruleConfig.setCardStrengthLevel(StrengthLevel.EXTREME);

        Config[] configs = {
                new Config(halfConfig, TrumpfSelectionMethod.MCTS_ON_SHIFT),
                new Config(ruleConfig)
        };

        double result = arena.runMatchWithConfigs(configs, 60);
        Arena.resultLogger.info("MCTS_ON_SHIFT scored {}% of RULE_BASED points (>100 means MCTS_ON_SHIFT wins)", result);
    }
}
