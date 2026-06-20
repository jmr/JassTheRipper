package to.joeli.jass.client.strategy.mcts;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import to.joeli.jass.client.game.Game;
import to.joeli.jass.client.strategy.helpers.GameSessionBuilder;
import to.joeli.jass.client.strategy.mcts.src.Board;
import to.joeli.jass.client.strategy.mcts.src.Move;
import to.joeli.jass.client.strategy.training.networks.PgxPolicyValueEstimator;
import to.joeli.jass.game.cards.Card;

import java.io.File;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Tests that {@link PgxPlayoutSelectionPolicy} exposes the pgx policy head as a full PUCT
 * prior distribution ({@link PgxPlayoutSelectionPolicy#priors}) keyed by {@link CardMove},
 * and that {@link PgxPlayoutSelectionPolicy#getBestMove} is the argmax of that distribution.
 *
 * <p>Skipped automatically when the SavedModel directory is absent.
 */
public class PgxPlayoutSelectionPolicyTest {

    // Uses an imported gen model (gitignored); pick whichever is present.
    private static final String MODEL_PATH = "src/main/resources/models/pv_gen3_s128/export";

    private PgxPlayoutSelectionPolicy policy;

    @Before
    public void setUp() {
        Assume.assumeTrue(
                "Skipping PgxPlayoutSelectionPolicy tests: model not found at " + MODEL_PATH,
                new File(MODEL_PATH).isDirectory());
        PgxPolicyValueEstimator estimator = new PgxPolicyValueEstimator();
        estimator.loadModel(MODEL_PATH);
        policy = new PgxPlayoutSelectionPolicy(estimator);
    }

    private static Board cardSelectionBoard(Game game) {
        return JassBoard.constructCardSelectionJassBoard(
                game.getCurrentPlayer().getCards(), game, false, false, null, null);
    }

    @Test
    public void priorsKeyedByLegalCardMovesAndSumToOne() {
        Game game = GameSessionBuilder.startedClubsGame();
        Set<Card> legal = game.getCurrentPlayer().getCards();
        assertEquals("9 cards in hand at start", 9, legal.size());

        Map<Move, Double> priors = policy.priors(cardSelectionBoard(game));

        assertEquals("one prior entry per legal card", legal.size(), priors.size());

        double sum = 0.0;
        for (Map.Entry<Move, Double> e : priors.entrySet()) {
            // keys must be CardMoves whose card is one of the current player's legal cards
            Card card = ((to.joeli.jass.client.game.Move) e.getKey()).getPlayedCard();
            assertTrue("prior key card " + card + " must be legal", legal.contains(card));
            assertTrue("prior[" + card + "] > 0: " + e.getValue(), e.getValue() > 0.0);
            sum += e.getValue();
        }
        assertEquals("prior distribution sums to 1.0", 1.0, sum, 1e-4);
    }

    @Test
    public void getBestMoveIsArgmaxOfPriors() {
        Game game = GameSessionBuilder.startedClubsGame();
        Board board = cardSelectionBoard(game);

        Map<Move, Double> priors = policy.priors(board);
        Move argmax = priors.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow();

        assertEquals("getBestMove must equal the argmax of priors", argmax, policy.getBestMove(board));
    }
}
