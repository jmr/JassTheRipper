package to.joeli.jass.game.mode;

import to.joeli.jass.client.game.Game;
import to.joeli.jass.game.Trumpf;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.cards.Color;

import java.util.Comparator;
import java.util.Set;

import to.joeli.jass.game.cards.CardSet;

import static to.joeli.jass.game.mode.GeneralRules.calculateLastRoundBonus;
import static java.lang.String.valueOf;

class BottomUpMode extends Mode {
    private static final int FACTOR = 1;

    @Override
    public Trumpf getTrumpfName() {
        return Trumpf.UNDEUFE;
    }

    @Override
    public Color getTrumpfColor() {
        return null;
    }

    @Override
    public int getCode() {
        return 5;
    }

    @Override
    public int calculateRoundScore(int roundNumber, Set<Card> playedCards) {
        if(roundNumber == Game.LAST_ROUND_NUMBER) {
            return calculateLastRoundBonus(FACTOR) + calculateScore(playedCards);
        }
        return calculateScore(playedCards);
    }

    @Override
    public int calculateRoundScore(int roundNumber, long bits) {
        int score = 0;
        long remaining = bits;
        while (remaining != 0L) {
            score += CardSet.CARDS[Long.numberOfTrailingZeros(remaining)].getValue().getBottomUpScore();
            remaining &= remaining - 1;
        }
        if (roundNumber == Game.LAST_ROUND_NUMBER) score += calculateLastRoundBonus(FACTOR);
        return score;
    }

    @Override
    public Card determineWinningCard(long playedBits, Color roundColor) {
        if (roundColor == null) return null;
        long roundBits = playedBits & CardSet.COLOR_MASKS[roundColor.ordinal()];
        return roundBits == 0L ? null : CardSet.CARDS[Long.numberOfTrailingZeros(roundBits)];
    }

    @Override
    public int calculateScore(Set<Card> playedCards) {
        return FACTOR * playedCards.stream()
                .mapToInt(card -> card.getValue().getBottomUpScore())
                .sum();
    }



    @Override
    public boolean canPlayCard(Card card, Set<Card> alreadyPlayedCards, Color currentRoundColor, Set<Card> playerCards) {
        return canPlayCard(card, CardSet.toBits(alreadyPlayedCards), currentRoundColor, CardSet.toBits(playerCards));
    }

    @Override
    public boolean canPlayCard(Card card, long alreadyPlayedBits, Color currentRoundColor, long playerCardBits) {
        return GeneralRules.canPlayCard(card, alreadyPlayedBits, currentRoundColor, playerCardBits);
    }

    @Override
    public int getFactor() {
        return FACTOR;
    }

    @Override
    public Comparator<Card> createRankComparator() {
        return (card, card2) -> !card.isHigherThan(card2) ? 1 : -1;
    }

    @Override
    public String toString() {
        return valueOf(getTrumpfName());
    }
}
