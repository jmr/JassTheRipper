package to.joeli.jass.client.strategy.analysis;

import kotlin.Pair;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import to.joeli.jass.client.game.GameSession;
import to.joeli.jass.client.strategy.config.Config;
import to.joeli.jass.client.strategy.config.StrengthLevel;
import to.joeli.jass.client.strategy.helpers.GameSessionBuilder;
import to.joeli.jass.client.strategy.training.networks.PgxPolicyValueEstimator;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.mode.Mode;

import java.io.File;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.*;
import static to.joeli.jass.game.cards.Card.*;

/**
 * Smoke tests for {@link PgxPositionAnalyzer}, the shared core behind raw pgx trump/card play
 * ({@code --pgx-trump} / {@code --pgx-raw} in {@code JassTheRipperJassStrategy}) and the
 * {@code ApplicationAnalyze} CLI.
 *
 * <p>All tests are skipped automatically when the SavedModel directory is absent (see
 * {@link PgxPolicyValueEstimator#DEFAULT_MODEL_PATH}), same as {@code PgxPolicyValueEstimatorTest}.
 */
public class PgxPositionAnalyzerTest {

    private static final String MODEL_PATH = PgxPolicyValueEstimator.DEFAULT_MODEL_PATH;

    private static final Set<Card> SPADE_HEAVY_HAND = EnumSet.of(
            SPADE_ACE, SPADE_KING, SPADE_QUEEN, SPADE_JACK, SPADE_TEN,
            HEART_ACE, HEART_KING, HEART_TEN, DIAMOND_SEVEN);

    private PgxPositionAnalyzer analyzer;

    @Before
    public void setUp() {
        File modelDir = new File(MODEL_PATH);
        Assume.assumeTrue(
                "Skipping PgxPositionAnalyzer tests: model not found at " + MODEL_PATH,
                modelDir.isDirectory());

        PgxPolicyValueEstimator estimator = new PgxPolicyValueEstimator();
        estimator.loadModel(MODEL_PATH);
        // FAST_TEST keeps the determinization budget (10 x factor) small so the test is fast.
        Config config = new Config();
        config.getMctsConfig().setTrumpfStrengthLevel(StrengthLevel.FAST_TEST);
        analyzer = new PgxPositionAnalyzer(estimator, config, null);
    }

    @Test
    public void analyzeTrumpRanksAllSevenLegalModesWhenNotShifted() {
        GameSession session = GameSessionBuilder.newSession().createGameSession();
        TrumpAnalysis analysis = analyzer.analyzeTrump(session, SPADE_HEAVY_HAND, false);

        assertEquals("6 declarations + Schiebe", 7, analysis.getRanked().size());
        assertEquals(7, analysis.getMeanLogits().size());
        assertEquals(10, analysis.getNumDeterminizations()); // TRUMP_ROUND_MULTIPLIER(10) * FAST_TEST(1)
    }

    @Test
    public void analyzeTrumpExcludesSchiebeWhenShifted() {
        GameSession session = GameSessionBuilder.newSession().createGameSession();
        TrumpAnalysis analysis = analyzer.analyzeTrump(session, SPADE_HEAVY_HAND, true);

        assertEquals("6 declarations, no Schiebe after a pass", 6, analysis.getRanked().size());
        for (Pair<Mode, Double> entry : analysis.getRanked()) {
            assertNotEquals(Mode.shift(), entry.getFirst());
        }
    }

    @Test
    public void analyzeTrumpProbabilitiesSumToOne() {
        GameSession session = GameSessionBuilder.newSession().createGameSession();
        TrumpAnalysis analysis = analyzer.analyzeTrump(session, SPADE_HEAVY_HAND, false);

        double sum = 0;
        for (Pair<Mode, Double> entry : analysis.getRanked()) {
            sum += entry.getSecond();
        }
        assertEquals(1.0, sum, 1e-6);
    }

    @Test
    public void analyzeTrumpBestIsTopOfRanking() {
        GameSession session = GameSessionBuilder.newSession().createGameSession();
        TrumpAnalysis analysis = analyzer.analyzeTrump(session, SPADE_HEAVY_HAND, false);

        assertEquals(analysis.getRanked().get(0).getFirst(), analysis.getBest());
    }

    @Test
    public void analyzeTrumpIsSortedDescending() {
        GameSession session = GameSessionBuilder.newSession().createGameSession();
        TrumpAnalysis analysis = analyzer.analyzeTrump(session, SPADE_HEAVY_HAND, false);

        double previous = Double.POSITIVE_INFINITY;
        for (Pair<Mode, Double> entry : analysis.getRanked()) {
            assertTrue("ranking must be non-increasing", entry.getSecond() <= previous);
            previous = entry.getSecond();
        }
    }

    @Test
    public void analyzeTrumpUnderCheatingUsesSingleDeterminization() {
        Config config = new Config();
        config.getMctsConfig().setCheating(true);
        PgxPolicyValueEstimator estimator = new PgxPolicyValueEstimator();
        estimator.loadModel(MODEL_PATH);
        PgxPositionAnalyzer cheatingAnalyzer = new PgxPositionAnalyzer(estimator, config, null);

        GameSession session = GameSessionBuilder.newSession().createGameSession();
        TrumpAnalysis analysis = cheatingAnalyzer.analyzeTrump(session, SPADE_HEAVY_HAND, false);

        assertEquals(1, analysis.getNumDeterminizations());
    }
}
