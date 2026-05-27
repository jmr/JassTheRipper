package to.joeli.jass.game.cards;

import org.junit.Test;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;
import static to.joeli.jass.game.cards.Card.*;
import static to.joeli.jass.game.cards.Color.*;

public class CardSetTest {

    @Test
    public void colorMasks_noOverlap() {
        long all = 0;
        for (Color c : Color.values()) {
            long mask = CardSet.COLOR_MASKS[c.ordinal()];
            assertEquals("overlap for " + c, 0L, all & mask);
            all |= mask;
        }
        // 36 cards = 36 bits set
        assertEquals(36, Long.bitCount(all));
    }

    @Test
    public void colorMasks_containCorrectCards() {
        for (Card card : Card.values()) {
            long mask = CardSet.COLOR_MASKS[card.getColor().ordinal()];
            assertNotEquals(0L, mask & (1L << card.ordinal()));
        }
    }

    @Test
    public void jackBits_pointToJacks() {
        for (Color c : Color.values()) {
            long jackBit = CardSet.JACK_BITS[c.ordinal()];
            int idx = Long.numberOfTrailingZeros(jackBit);
            assertEquals(CardValue.JACK, CardSet.CARDS[idx].getValue());
            assertEquals(c, CardSet.CARDS[idx].getColor());
        }
    }

    @Test
    public void nineBits_pointToNines() {
        for (Color c : Color.values()) {
            long nineBit = CardSet.NINE_BITS[c.ordinal()];
            int idx = Long.numberOfTrailingZeros(nineBit);
            assertEquals(CardValue.NINE, CardSet.CARDS[idx].getValue());
            assertEquals(c, CardSet.CARDS[idx].getColor());
        }
    }

    @Test
    public void toBits_toEnumSet_roundtrip() {
        Set<Card> original = EnumSet.of(HEART_ACE, DIAMOND_JACK, CLUB_NINE, SPADE_SIX);
        long bits = CardSet.toBits(original);
        assertEquals(original, CardSet.toEnumSet(bits));
    }

    @Test
    public void toBits_emptySet() {
        assertEquals(0L, CardSet.toBits(EnumSet.noneOf(Card.class)));
    }

    @Test
    public void size_andIsEmpty() {
        long bits = CardSet.toBits(EnumSet.of(HEART_ACE, SPADE_TEN));
        assertEquals(2, CardSet.size(bits));
        assertFalse(CardSet.isEmpty(bits));
        assertTrue(CardSet.isEmpty(0L));
    }

    @Test
    public void contains_card() {
        long bits = CardSet.toBits(EnumSet.of(HEART_ACE, SPADE_TEN));
        assertTrue(CardSet.contains(bits, HEART_ACE));
        assertFalse(CardSet.contains(bits, HEART_SIX));
    }

    @Test
    public void pickRandom_singleCard() {
        long bits = CardSet.toBits(EnumSet.of(DIAMOND_KING));
        assertEquals(DIAMOND_KING, CardSet.pickRandom(bits, new Random(0)));
    }

    @Test
    public void pickRandom_distributesUniformly() {
        Set<Card> cards = EnumSet.of(HEART_SIX, HEART_SEVEN, HEART_EIGHT);
        long bits = CardSet.toBits(cards);
        int[] counts = new int[36];
        Random rng = new Random(1);
        int trials = 30_000;
        for (int i = 0; i < trials; i++) counts[CardSet.pickRandom(bits, rng).ordinal()]++;
        for (Card c : cards) {
            int count = counts[c.ordinal()];
            assertTrue("count " + count + " for " + c, count > 8000 && count < 12000);
        }
    }
}
