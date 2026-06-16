package to.joeli.jass.client.strategy.training.networks;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import to.joeli.jass.client.game.Game;
import to.joeli.jass.client.strategy.helpers.GameSessionBuilder;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.cards.Color;
import to.joeli.jass.game.mode.Mode;

import java.io.File;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Smoke tests for {@link PgxPolicyValueEstimator}.
 *
 * <p>All tests are skipped automatically when the SavedModel directory is absent.
 * To generate a random-init model, run:
 * <pre>
 *   python ../pgx/scripts/export_pv_savedmodel.py \
 *       --out src/main/resources/models/pgx_pv/export
 * </pre>
 */
public class PgxPolicyValueEstimatorTest {

    private static final String MODEL_PATH = PgxPolicyValueEstimator.DEFAULT_MODEL_PATH;

    private PgxPolicyValueEstimator estimator;

    @Before
    public void setUp() {
        // Skip all tests if the SavedModel hasn't been generated yet.
        File modelDir = new File(MODEL_PATH);
        Assume.assumeTrue(
                "Skipping PgxPolicyValueEstimator tests: model not found at " + MODEL_PATH,
                modelDir.isDirectory());

        estimator = new PgxPolicyValueEstimator();
        estimator.loadModel(MODEL_PATH);
    }

    // ── Forward pass output shape & finiteness ────────────────────────────────

    @Test
    public void predictValueReturnsFiniteScalar() {
        Game game = GameSessionBuilder.startedClubsGame();
        double value = estimator.predictValue(game);
        assertTrue("value should be finite", Double.isFinite(value));
    }

    @Test
    public void predictValueIsInReasonableRange() {
        // With a random-init model the value may be anywhere, but once trained it
        // should be within [-157, 157] (total Jass points).
        Game game = GameSessionBuilder.startedClubsGame();
        double value = estimator.predictValue(game);
        assertTrue("value should be >= -157: " + value, value >= -158.0);
        assertTrue("value should be <= 157: "  + value, value <=  158.0);
    }

    @Test
    public void predictPriorReturnsDistributionOverLegalCards() {
        Game game = GameSessionBuilder.startedClubsGame();
        Set<Card> legal = game.getCurrentPlayer().getCards();
        assertFalse("legal cards must not be empty", legal.isEmpty());

        Map<Card, Float> priors = estimator.predictPriorOverLegal(game, legal);

        assertEquals("prior map size == legal card count", legal.size(), priors.size());
        for (Card card : legal) {
            assertTrue("all legal cards present in prior map", priors.containsKey(card));
            Float p = priors.get(card);
            assertNotNull(p);
            assertTrue("prior[" + card + "] >= 0: " + p, p >= 0f);
        }

        // Distribution should sum to ~1
        double sum = priors.values().stream().mapToDouble(Float::doubleValue).sum();
        assertEquals("prior distribution sums to 1.0", 1.0, sum, 1e-4);
    }

    @Test
    public void predictPriorCoversAllNineStartingCards() {
        // At game start every player has 9 cards, all legal.
        Game game = GameSessionBuilder.startedClubsGame();
        Set<Card> legal = game.getCurrentPlayer().getCards();
        assertEquals("9 cards in hand at start", 9, legal.size());

        Map<Card, Float> priors = estimator.predictPriorOverLegal(game, legal);
        assertEquals(9, priors.size());
    }

    @Test
    public void predictPriorIsNonzeroForAllLegalCards() {
        // After softmax + masking + renorm, every legal card should get > 0 prior
        // (as long as no raw logit is -∞, which it shouldn't be for a finite-weight model).
        Game game = GameSessionBuilder.startedClubsGame();
        Set<Card> legal = game.getCurrentPlayer().getCards();
        Map<Card, Float> priors = estimator.predictPriorOverLegal(game, legal);

        for (Card card : legal) {
            assertTrue("prior[" + card + "] > 0", priors.get(card) > 0f);
        }
    }

    @Test
    public void predictValueTopDownMode() {
        // Sanity: model handles non-trump modes without error.
        Game game = GameSessionBuilder.startedGame(Mode.topDown());
        double value = estimator.predictValue(game);
        assertTrue("Obenabe value is finite", Double.isFinite(value));
    }

    @Test
    public void predictValueDiamondsMode() {
        Game game = GameSessionBuilder.startedGame(Mode.trump(Color.DIAMONDS));
        double value = estimator.predictValue(game);
        assertTrue("Diamonds value is finite", Double.isFinite(value));
    }

    @Test
    public void multipleForwardPassesAreIdempotent() {
        // The same game state should produce the same value every time.
        Game game = GameSessionBuilder.startedClubsGame();
        double v1 = estimator.predictValue(game);
        double v2 = estimator.predictValue(game);
        assertEquals("repeated forward pass must return identical value", v1, v2, 1e-6);
    }
}
