package to.joeli.jass.client.strategy.training;

import org.junit.Test;
import to.joeli.jass.client.game.Game;
import to.joeli.jass.client.game.GameSession;
import to.joeli.jass.client.game.Player;
import to.joeli.jass.client.strategy.helpers.CardSelectionHelper;
import to.joeli.jass.client.strategy.helpers.GameSessionBuilder;
import to.joeli.jass.client.game.Move;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.cards.CardSet;
import to.joeli.jass.game.cards.Color;
import to.joeli.jass.game.mode.Mode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Deterministic regression test for the random playout loop.
 *
 * Captures the exact sequence of 36 cards played in a single game with a fixed seed.
 * After the long-bitmask refactor, this sequence must be identical — any divergence
 * in canPlayCard / getCardsPossibleToPlay logic will show up here.
 *
 * To recapture the baseline (e.g. after an intentional logic change):
 *   set EXPECTED_SEQUENCE = null, run once, copy the printed sequence, paste it back.
 */
public class PlayoutRegressionTest {

    private static final String EXPECTED_SEQUENCE = "Player0:C10,Player1:H7,Player2:H8,Player3:HA,Player3:D6,Player0:D7,Player1:H10,Player2:DA,Player1:D8,Player2:DK,Player3:H6,Player0:S10,Player3:SQ,Player0:H9,Player1:S9,Player2:SK,Player0:HQ,Player1:C7,Player2:C8,Player3:HJ,Player3:SJ,Player0:S8,Player1:D10,Player2:SA,Player2:D9,Player3:S6,Player0:CJ,Player1:DJ,Player1:DQ,Player2:CQ,Player3:S7,Player0:C6,Player1:CK,Player2:C9,Player3:CA,Player0:HK";

    @Test
    public void deterministicPlayout_seed42() {
        GameSession session = GameSessionBuilder.newSession().createGameSession();
        List<Card> deck = new ArrayList<>(Arrays.asList(Card.values()));
        Random rng = new Random(42);

        Collections.shuffle(deck, rng);
        session.dealCards(deck);
        session.startNewGame(Mode.trump(Color.HEARTS), false);

        Game game = session.getCurrentGame();
        List<String> moves = new ArrayList<>();

        while (!game.gameFinished()) {
            while (!game.getCurrentRound().roundFinished()) {
                Player p = game.getCurrentPlayer();
                long possibleBits = CardSelectionHelper.getCardsPossibleToPlayBits(p.getCardBits(), game);
                Card card = possibleBits == 0L
                        ? p.getCards().iterator().next()
                        : CardSet.pickRandom(possibleBits, rng);
                Move move = new Move(p, card);
                game.makeMove(move);
                p.onMoveMade(move);
                moves.add(p.getName() + ":" + card);
            }
            session.startNextRound();
        }

        String actual = String.join(",", moves);

        if (EXPECTED_SEQUENCE == null) {
            fail("First run — paste this as EXPECTED_SEQUENCE:\n\"" + actual + "\"");
        }
        assertEquals(EXPECTED_SEQUENCE, actual);
    }
}
