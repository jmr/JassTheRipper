package to.joeli.jass.client.strategy.helpers;

import to.joeli.jass.client.game.Game;
import to.joeli.jass.client.game.Move;
import to.joeli.jass.client.game.Player;
import to.joeli.jass.client.game.Round;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.mode.Mode;

import java.util.Arrays;
import java.util.List;

/**
 * Encodes a <em>fully-determinized</em> {@link Game} into the feature representation consumed
 * by the pgx {@code PolicyValueNet}: a card matrix {@code (36 × 12)} and a header {@code (20,)},
 * both {@code float32}.
 *
 * <p>This mirrors {@code pgx/_src/games/jass.py::value_features()} exactly.
 *
 * <h3>Card index mapping</h3>
 * pgx card index = {@code suit * 9 + rank_idx}, where:
 * <ul>
 *   <li>suit: ♦=0 ♥=1 ♠=2 ♣=3  — identical to Java {@code Color.getValue()}
 *   <li>rank_idx: 6→0, 7→1, …, A→8  — equals {@code CardValue.getRank() - 1}
 * </ul>
 * So {@code pgxIndex(card) = card.getColor().getValue() * 9 + (card.getValue().getRank() - 1)}.
 * Note that Java {@code Card.ordinal()} uses a different suit order (H,D,C,S) and is
 * <em>not</em> the same as the pgx index.
 *
 * <h3>Card matrix columns (player-relative)</h3>
 * <pre>
 *  0   I hold the card
 *  1   partner holds it
 *  2   left opponent holds it
 *  3   right opponent holds it
 *  4   my team has collected it (from any completed trick)
 *  5   opponent team has collected it
 *  6   card is currently in the trick (played this round)
 *  7   I played it in the current trick
 *  8   partner played it
 *  9   left opponent played it
 * 10   right opponent played it
 * 11   card is trump in the current mode
 * </pre>
 * Player-relative offsets: me=0, left-opp=+1, partner=+2, right-opp=+3 (mod 4 on seat IDs).
 *
 * <h3>Header (20 bits)</h3>
 * <pre>
 *  [0:6]   trump mode one-hot: ♦ ♥ ♠ ♣ Obenabe Undeufe  (Mode.getCode() 0–5)
 *  [6]     forehand_passed (game.isShifted())
 *  [7:16]  trick_num one-hot (0–8, equals Round.getRoundNumber())
 *  [16:20] trick_leader one-hot (seat IDs 0–3)
 * </pre>
 */
public final class PgxFeatureEncoder {

    public static final int NUM_CARDS = 36;
    public static final int CARD_MATRIX_COLS = 12;
    public static final int HEADER_SIZE = 20;

    /** Maps {@code Card.ordinal()} → pgx card index (0–35). */
    private static final int[] JAVA_TO_PGX = new int[NUM_CARDS];

    /** Maps pgx card index (0–35) → Java {@link Card} enum constant. */
    public static final Card[] PGX_TO_JAVA = new Card[NUM_CARDS];

    static {
        for (Card card : Card.values()) {
            // Color.getValue(): DIAMONDS=0, HEARTS=1, SPADES=2, CLUBS=3 — same as pgx suit order.
            // CardValue.getRank(): SIX=1 … ACE=9 → subtract 1 for pgx rank_idx 0–8.
            int pgxIdx = card.getColor().getValue() * 9 + (card.getValue().getRank() - 1);
            JAVA_TO_PGX[card.ordinal()] = pgxIdx;
            PGX_TO_JAVA[pgxIdx] = card;
        }
    }

    private PgxFeatureEncoder() {}

    /**
     * Returns the pgx card index (0–35) for the given Java {@link Card}.
     * This is {@code color.getValue() * 9 + (rank - 1)}.
     */
    public static int pgxIndex(Card card) {
        return JAVA_TO_PGX[card.ordinal()];
    }

    /**
     * Returns the Java {@link Card} for the given pgx card index (0–35).
     */
    public static Card javaCard(int pgxIndex) {
        return PGX_TO_JAVA[pgxIndex];
    }

    // ── Feature container ───────────────────────────────────────────────────

    /** Holds the result of {@link #encode(Game)}. */
    public static final class Features {
        /** Card matrix {@code [36][12]}, float32. */
        public final float[][] cardMatrix;
        /** Header {@code [20]}, float32. */
        public final float[] header;

        Features(float[][] cardMatrix, float[] header) {
            this.cardMatrix = cardMatrix;
            this.header = header;
        }
    }

    // ── Encoding ────────────────────────────────────────────────────────────

    /**
     * Encodes the current game state from the perspective of the <em>current player</em>.
     *
     * <p>The game must be fully determinized — all four {@code player.getCards()} sets must
     * be populated with the correct hands (as produced by
     * {@link CardKnowledgeBase#sampleCardDeterminizationToPlayers}).
     *
     * @param game a fully determinized {@link Game}
     * @return the {@code (36,12)} card matrix and {@code (20,)} header as float32 arrays
     */
    public static Features encode(Game game) {
        float[][] cm = new float[NUM_CARDS][CARD_MATRIX_COLS];
        float[] hd = new float[HEADER_SIZE];

        Player me = game.getCurrentPlayer();
        int meSeat      = me.getSeatId();
        int partnerSeat = (meSeat + 2) & 3;
        int leftOppSeat = (meSeat + 1) & 3;
        int rightOppSeat= (meSeat + 3) & 3;

        // Build seat-id → Player map (seat IDs are always 0–3)
        Player[] bySeat = new Player[4];
        for (Player p : game.getPlayers()) {
            bySeat[p.getSeatId()] = p;
        }

        // ── Columns 4-5: who collected each card in completed tricks ──
        // In Jass the winner of each trick collects all 4 cards played in that trick.
        boolean[][] collectedBySeat = new boolean[4][NUM_CARDS];
        for (Round prevRound : game.getPreviousRounds()) {
            Player winner = prevRound.getWinner();
            if (winner == null) continue;  // defensive (shouldn't happen)
            int winnerSeat = winner.getSeatId();
            for (Move move : prevRound.getMoves()) {
                collectedBySeat[winnerSeat][pgxIndex(move.getPlayedCard())] = true;
            }
        }

        // ── Columns 6-10: current trick ──
        // trick_cards_by_seat[seatId] = pgx index of card played this trick, or -1
        int[] trickCardBySeat = new int[4];
        Arrays.fill(trickCardBySeat, -1);
        for (Move move : game.getCurrentRound().getMoves()) {
            trickCardBySeat[move.getPlayer().getSeatId()] = pgxIndex(move.getPlayedCard());
        }

        // ── Trick leader: first player to act in the current trick ──
        List<Move> currentMoves = game.getCurrentRound().getMoves();
        int trickLeaderSeat = currentMoves.isEmpty()
                ? meSeat   // no moves yet → I lead (current player == trick leader)
                : currentMoves.get(0).getPlayer().getSeatId();

        Mode mode = game.getCurrentRoundMode();
        boolean isTrumpMode = mode.isTrumpfMode();

        // ── Fill card matrix (one row per card in pgx index order) ──
        for (Card card : Card.values()) {
            int r = pgxIndex(card);
            float[] row = cm[r];

            // Columns 0-3: who currently holds the card (after determinization)
            row[0] = bySeat[meSeat].getCards().contains(card)       ? 1f : 0f;
            row[1] = bySeat[partnerSeat].getCards().contains(card)   ? 1f : 0f;
            row[2] = bySeat[leftOppSeat].getCards().contains(card)   ? 1f : 0f;
            row[3] = bySeat[rightOppSeat].getCards().contains(card)  ? 1f : 0f;

            // Columns 4-5: which team collected this card
            row[4] = (collectedBySeat[meSeat][r] || collectedBySeat[partnerSeat][r])    ? 1f : 0f;
            row[5] = (collectedBySeat[leftOppSeat][r] || collectedBySeat[rightOppSeat][r]) ? 1f : 0f;

            // Column 6: in current trick (any seat played it)
            boolean inTrick = false;
            for (int s = 0; s < 4; s++) {
                if (trickCardBySeat[s] == r) { inTrick = true; break; }
            }
            row[6]  = inTrick ? 1f : 0f;

            // Columns 7-10: who played it in the current trick (player-relative)
            row[7]  = (trickCardBySeat[meSeat]       == r) ? 1f : 0f;
            row[8]  = (trickCardBySeat[partnerSeat]   == r) ? 1f : 0f;
            row[9]  = (trickCardBySeat[leftOppSeat]   == r) ? 1f : 0f;
            row[10] = (trickCardBySeat[rightOppSeat]  == r) ? 1f : 0f;

            // Column 11: is trump (only in a trump-suit mode)
            row[11] = (isTrumpMode && card.getColor() == mode.getTrumpfColor()) ? 1f : 0f;
        }

        // ── Fill header ──
        int modeCode = mode.getCode();   // 0-3 for trump suits, 4=Obenabe, 5=Undeufe
        if (modeCode >= 0 && modeCode <= 5) {
            hd[modeCode] = 1f;           // [0:6] trump mode one-hot
        }
        hd[6] = game.isShifted() ? 1f : 0f;   // [6] forehand_passed

        int trickNum = game.getCurrentRound().getRoundNumber();   // 0-8
        if (trickNum >= 0 && trickNum <= 8) {
            hd[7 + trickNum] = 1f;       // [7:16] trick_num one-hot
        }
        hd[16 + trickLeaderSeat] = 1f;  // [16:20] trick_leader one-hot

        return new Features(cm, hd);
    }
}
