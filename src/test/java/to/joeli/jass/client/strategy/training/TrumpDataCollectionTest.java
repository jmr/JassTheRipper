package to.joeli.jass.client.strategy.training;

import org.junit.Test;
import to.joeli.jass.client.strategy.config.Config;
import to.joeli.jass.client.strategy.config.MCTSConfig;
import to.joeli.jass.client.strategy.config.StrengthLevel;

/**
 * Generates trump selection training data.
 *
 * FAST run  (~1 h):   100 deals × 3 modes × 1 game = 300 games
 * EXTREME run (~9 h): 150 deals × 3 modes × 1 game = 450 games
 *
 * Output: src/main/resources/trump_data.csv
 */
public class TrumpDataCollectionTest {

    @Test
    public void collectFast() {
        MCTSConfig cfg = new MCTSConfig();
        cfg.setCardStrengthLevel(StrengthLevel.FAST);
        new TrumpDataCollector(new Config(cfg), 42, "src/main/resources/trump_data_fast.csv").collect(100);
    }

    @Test
    public void collectExtreme() {
        MCTSConfig cfg = new MCTSConfig();
        cfg.setCardStrengthLevel(StrengthLevel.EXTREME);
        new TrumpDataCollector(new Config(cfg), 42, "src/main/resources/trump_data_extreme.csv").collect(150);
    }
}
