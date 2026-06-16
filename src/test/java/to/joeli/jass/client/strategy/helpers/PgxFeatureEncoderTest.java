package to.joeli.jass.client.strategy.helpers;

import org.junit.Test;
import to.joeli.jass.client.game.Game;
import to.joeli.jass.client.game.GameSession;
import to.joeli.jass.client.game.Move;
import to.joeli.jass.client.game.Player;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.cards.Color;
import to.joeli.jass.game.mode.Mode;

import java.util.Set;

import static org.junit.Assert.*;
import static to.joeli.jass.game.cards.Card.*;

/**
 * Unit tests for {@link PgxFeatureEncoder}.
 *
 * Verifies that the pgx card index mapping is correct and that game-state
 * features match the expected values from pgx's value_features() documentation.
 */
public class PgxFeatureEncoderTest {

    private static final float DELTA = 1e-5f;
    private static final int CARD_MATRIX_COLS = PgxFeatureEncoder.CARD_MATRIX_COLS;  // 12

    // ── Card index mapping ────────────────────────────────────────────────────

    @Test
    public void pgxIndexMatchesSuitTimesNinePlusRankMinusOne() {
        for (Card card : Card.values()) {
            int expectedPgxIdx = card.getColor().getValue() * 9 + (card.getValue().getRank() - 1);
            assertEquals(
                    "pgxIndex(" + card + ")",
                    expectedPgxIdx,
                    PgxFeatureEncoder.pgxIndex(card));
        }
    }

    @Test
    public void pgxIndexRoundTripsViaJavaCard() {
        for (Card card : Card.values()) {
            int idx = PgxFeatureEncoder.pgxIndex(card);
            Card roundTripped = PgxFeatureEncoder.javaCard(idx);
            assertEquals("pgxIndex→javaCard round-trip for " + card, card, roundTripped);
        }
    }

    @Test
    public void firstPgxCardIsDiamondSix() {
        // pgx suit 0 = ♦, rank_idx 0 = 6  →  DIAMOND_SIX
        assertEquals(DIAMOND_SIX, PgxFeatureEncoder.javaCard(0));
        assertEquals(0, PgxFeatureEncoder.pgxIndex(DIAMOND_SIX));
    }

    @Test
    public void ninthPgxCardIsDiamondAce() {
        // pgx index 8 = ♦ rank_idx 8 = A
        assertEquals(DIAMOND_ACE, PgxFeatureEncoder.javaCard(8));
        assertEquals(8, PgxFeatureEncoder.pgxIndex(DIAMOND_ACE));
    }

    @Test
    public void tenthPgxCardIsHeartSix() {
        // pgx suit 1 = ♥, rank_idx 0 = 6  →  index 9
        assertEquals(HEART_SIX, PgxFeatureEncoder.javaCard(9));
        assertEquals(9, PgxFeatureEncoder.pgxIndex(HEART_SIX));
    }

    @Test
    public void lastPgxCardIsClubAce() {
        // pgx suit 3 = ♣, rank_idx 8 = A  →  index 35
        assertEquals(CLUB_ACE, PgxFeatureEncoder.javaCard(35));
        assertEquals(35, PgxFeatureEncoder.pgxIndex(CLUB_ACE));
    }

    @Test
    public void allPgxIndiciesAreUnique() {
        Set<Integer> seen = new java.util.HashSet<>();
        for (Card card : Card.values()) {
            int idx = PgxFeatureEncoder.pgxIndex(card);
            assertTrue("Duplicate pgx index " + idx + " for " + card, seen.add(idx));
            assertTrue("pgx index out of range: " + idx, idx >= 0 && idx < 36);
        }
    }

    @Test
    public void colorValueMatchesPgxSuitOrder() {
        // pgx: ♦=0 ♥=1 ♠=2 ♣=3 — must match Java Color.getValue()
        assertEquals(0, Color.DIAMONDS.getValue());
        assertEquals(1, Color.HEARTS.getValue());
        assertEquals(2, Color.SPADES.getValue());
        assertEquals(3, Color.CLUBS.getValue());
    }

    // ── Feature encoding ──────────────────────────────────────────────────────

    /** Helper: get a single cell from the card matrix. */
    private static float cm(PgxFeatureEncoder.Features feat, Card card, int col) {
        return feat.cardMatrix[PgxFeatureEncoder.pgxIndex(card)][col];
    }

    @Test
    public void cardMatrixShape() {
        Game game = GameSessionBuilder.startedClubsGame();
        PgxFeatureEncoder.Features feat = PgxFeatureEncoder.encode(game);
        assertEquals(36, feat.cardMatrix.length);
        for (float[] row : feat.cardMatrix) {
            assertEquals(CARD_MATRIX_COLS, row.length);
        }
        assertEquals(PgxFeatureEncoder.HEADER_SIZE, feat.header.length);
    }

    @Test
    public void myHandCardsHaveCol0Set() {
        Game game = GameSessionBuilder.startedClubsGame();
        Player me = game.getCurrentPlayer();
        PgxFeatureEncoder.Features feat = PgxFeatureEncoder.encode(game);

        for (Card card : Card.values()) {
            float expected = me.getCards().contains(card) ? 1f : 0f;
            assertEquals("col0 for " + card, expected, cm(feat, card, 0), DELTA);
        }
    }

    @Test
    public void noCardsCollectedAtStartOfGame() {
        Game game = GameSessionBuilder.startedClubsGame();
        PgxFeatureEncoder.Features feat = PgxFeatureEncoder.encode(game);

        // At the very start of the game, no tricks have been completed —
        // columns 4 and 5 (team collection) must be all-zero.
        for (Card card : Card.values()) {
            assertEquals("col4 for " + card, 0f, cm(feat, card, 4), DELTA);
            assertEquals("col5 for " + card, 0f, cm(feat, card, 5), DELTA);
        }
    }

    @Test
    public void noCardsInTrickAtStartOfGame() {
        Game game = GameSessionBuilder.startedClubsGame();
        PgxFeatureEncoder.Features feat = PgxFeatureEncoder.encode(game);

        // No cards in the current trick at the start.
        for (Card card : Card.values()) {
            assertEquals("col6 for " + card, 0f, cm(feat, card, 6), DELTA);
        }
    }

    @Test
    public void cardsInTrickAfterOneMoveIsSet() {
        // Play one card and check that it appears in columns 6 and 7 (I played it)
        GameSession session = GameSessionBuilder.newSession().withStartedGame(Mode.trump(Color.CLUBS)).createGameSession();
        Game game = session.getCurrentGame();
        Player firstPlayer = game.getCurrentPlayer();
        Card firstCard = firstPlayer.getCards().iterator().next();

        // Make the move in the session (updates game state)
        session.makeMove(new Move(firstPlayer, firstCard));
        firstPlayer.onMoveMade(new Move(firstPlayer, firstCard));

        // Now encode from the perspective of the NEXT player (firstPlayer has already played)
        Player nextPlayer = game.getCurrentPlayer();
        PgxFeatureEncoder.Features feat = PgxFeatureEncoder.encode(game);

        // The card that was played should be in the trick (col 6 = 1)
        assertEquals("col6 for played card " + firstCard, 1f, cm(feat, firstCard, 6), DELTA);

        // The played card is NOT in any player's hand anymore
        assertEquals("col0 for played card should be 0", 0f, cm(feat, firstCard, 0), DELTA);
    }

    @Test
    public void trumpModeCol11IsSetForTrumpCards() {
        Game game = GameSessionBuilder.startedClubsGame();  // clubs is trump
        PgxFeatureEncoder.Features feat = PgxFeatureEncoder.encode(game);

        for (Card card : Card.values()) {
            float expected = card.getColor() == Color.CLUBS ? 1f : 0f;
            assertEquals("col11 (is trump) for " + card, expected, cm(feat, card, 11), DELTA);
        }
    }

    @Test
    public void col11IsZeroForTopDownMode() {
        Game game = GameSessionBuilder.startedGame(Mode.topDown());
        PgxFeatureEncoder.Features feat = PgxFeatureEncoder.encode(game);

        for (Card card : Card.values()) {
            assertEquals("col11 should be 0 for Obenabe: " + card, 0f, cm(feat, card, 11), DELTA);
        }
    }

    // ── Header ────────────────────────────────────────────────────────────────

    @Test
    public void headerClubsTrumpModeIsIndex3() {
        Game game = GameSessionBuilder.startedClubsGame();  // clubs = mode code 3
        PgxFeatureEncoder.Features feat = PgxFeatureEncoder.encode(game);

        // Header [0:6] = trump one-hot, clubs = index 3
        assertEquals(0f, feat.header[0], DELTA);   // ♦
        assertEquals(0f, feat.header[1], DELTA);   // ♥
        assertEquals(0f, feat.header[2], DELTA);   // ♠
        assertEquals(1f, feat.header[3], DELTA);   // ♣ (clubs)
        assertEquals(0f, feat.header[4], DELTA);   // Obenabe
        assertEquals(0f, feat.header[5], DELTA);   // Undeufe
    }

    @Test
    public void headerTopDownModeIsIndex4() {
        Game game = GameSessionBuilder.startedGame(Mode.topDown());
        PgxFeatureEncoder.Features feat = PgxFeatureEncoder.encode(game);

        assertEquals(0f, feat.header[0], DELTA);
        assertEquals(0f, feat.header[1], DELTA);
        assertEquals(0f, feat.header[2], DELTA);
        assertEquals(0f, feat.header[3], DELTA);
        assertEquals(1f, feat.header[4], DELTA);   // Obenabe
        assertEquals(0f, feat.header[5], DELTA);
    }

    @Test
    public void headerDiamondsTrumpModeIsIndex0() {
        Game game = GameSessionBuilder.startedGame(Mode.trump(Color.DIAMONDS));
        PgxFeatureEncoder.Features feat = PgxFeatureEncoder.encode(game);

        assertEquals(1f, feat.header[0], DELTA);   // ♦
        assertEquals(0f, feat.header[1], DELTA);
        assertEquals(0f, feat.header[2], DELTA);
        assertEquals(0f, feat.header[3], DELTA);
    }

    @Test
    public void headerForehandPassedDefaultFalse() {
        Game game = GameSessionBuilder.startedClubsGame();
        PgxFeatureEncoder.Features feat = PgxFeatureEncoder.encode(game);
        // The default test game uses shiftCards but is started (not shifted), so forehand_passed = 0
        assertEquals(0f, feat.header[6], DELTA);
    }

    @Test
    public void headerTrickNumOneHotAtRound0() {
        Game game = GameSessionBuilder.startedClubsGame();
        PgxFeatureEncoder.Features feat = PgxFeatureEncoder.encode(game);

        // header[7..15] = trick_num one-hot (0-8); round 0 → header[7] = 1
        assertEquals(1f, feat.header[7], DELTA);   // trick_num = 0
        for (int i = 8; i <= 15; i++) {
            assertEquals("header[" + i + "] should be 0", 0f, feat.header[i], DELTA);
        }
    }

    @Test
    public void headerTrickLeaderOneHotMatchesCurrentPlayerAtTrickStart() {
        // At the start of a trick (no moves yet), the trick leader IS the current player
        Game game = GameSessionBuilder.startedClubsGame();
        int leaderSeat = game.getCurrentPlayer().getSeatId();
        PgxFeatureEncoder.Features feat = PgxFeatureEncoder.encode(game);

        // header[16..19] = trick_leader one-hot
        for (int s = 0; s < 4; s++) {
            float expected = (s == leaderSeat) ? 1f : 0f;
            assertEquals("header[" + (16 + s) + "] leader seat " + s,
                    expected, feat.header[16 + s], DELTA);
        }
    }

    @Test
    public void headerTrickLeaderUnchangedAfterFirstMove() {
        // After the first card is played in a trick, the trick leader doesn't change
        GameSession session = GameSessionBuilder.newSession()
                .withStartedGame(Mode.trump(Color.CLUBS)).createGameSession();
        Game game = session.getCurrentGame();
        int expectedLeaderSeat = game.getCurrentPlayer().getSeatId();

        // Play one card
        Player firstPlayer = game.getCurrentPlayer();
        Card firstCard = firstPlayer.getCards().iterator().next();
        session.makeMove(new Move(firstPlayer, firstCard));
        firstPlayer.onMoveMade(new Move(firstPlayer, firstCard));

        // Encode from the next player's perspective
        PgxFeatureEncoder.Features feat = PgxFeatureEncoder.encode(game);

        // Trick leader should still be the first player
        assertEquals("trick leader seat mismatch",
                1f, feat.header[16 + expectedLeaderSeat], DELTA);
    }
}
