package to.joeli.jass.game.cards;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

public final class CardSet {

    public static final Card[] CARDS;
    public static final long[] COLOR_MASKS = new long[4];
    public static final long[] JACK_BITS   = new long[4];
    public static final long[] NINE_BITS   = new long[4];

    static {
        CARDS = Card.values();
        for (Color c : Color.values()) {
            int start = -1;
            for (Card card : CARDS) {
                if (card.getColor() == c) {
                    start = card.ordinal();
                    break;
                }
            }
            int idx = c.ordinal();
            COLOR_MASKS[idx] = 0x1FFL << start;
            JACK_BITS[idx]   = 1L << (start + 5);
            NINE_BITS[idx]   = 1L << (start + 3);
        }
    }

    private CardSet() {}

    public static long toBits(Set<Card> cards) {
        long bits = 0L;
        for (Card card : cards) bits |= 1L << card.ordinal();
        return bits;
    }

    public static EnumSet<Card> toEnumSet(long bits) {
        EnumSet<Card> set = EnumSet.noneOf(Card.class);
        long remaining = bits;
        while (remaining != 0) {
            int idx = Long.numberOfTrailingZeros(remaining);
            set.add(CARDS[idx]);
            remaining &= remaining - 1;
        }
        return set;
    }

    public static int size(long bits) {
        return Long.bitCount(bits);
    }

    public static boolean isEmpty(long bits) {
        return bits == 0;
    }

    public static boolean contains(long bits, Card card) {
        return (bits & (1L << card.ordinal())) != 0;
    }

    public static Card pickRandom(long bits, Random rng) {
        int target = rng.nextInt(Long.bitCount(bits));
        long remaining = bits;
        for (int i = 0; i < target; i++) remaining &= remaining - 1;
        return CARDS[Long.numberOfTrailingZeros(remaining)];
    }
}
