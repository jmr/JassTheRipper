package to.joeli.jass.game.mode;

import to.joeli.jass.client.game.Game;
import to.joeli.jass.game.Trumpf;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.cards.CardSet;
import to.joeli.jass.game.cards.CardValue;
import to.joeli.jass.game.cards.Color;

import java.util.Comparator;
import java.util.Set;

class TrumpfColorMode extends Mode {

	private final Color trumpfColor;

	public TrumpfColorMode(Color trumpfColor) {
		this.trumpfColor = trumpfColor;
	}

	@Override
	public Trumpf getTrumpfName() {
		return Trumpf.TRUMPF;
	}

	@Override
	public Color getTrumpfColor() {
		return trumpfColor;
	}

	@Override
	public int getCode() {
		return trumpfColor.getValue();
	}

	@Override
	public int calculateRoundScore(int roundNumber, Set<Card> playedCards) {
		if (roundNumber == Game.LAST_ROUND_NUMBER) {
			return GeneralRules.calculateLastRoundBonus(getFactor()) + calculateScore(playedCards);
		}
		return calculateScore(playedCards);
	}

	@Override
	public int calculateRoundScore(int roundNumber, long bits) {
		int score = 0;
		long remaining = bits;
		while (remaining != 0L) {
			score += getCardScore(CardSet.CARDS[Long.numberOfTrailingZeros(remaining)]);
			remaining &= remaining - 1;
		}
		if (roundNumber == Game.LAST_ROUND_NUMBER) score += GeneralRules.calculateLastRoundBonus(1);
		return score;
	}

	@Override
	public Card determineWinningCard(long playedBits, Color roundColor) {
		long trumpfMask = CardSet.COLOR_MASKS[trumpfColor.ordinal()];
		long trumpfPlayed = playedBits & trumpfMask;
		if (trumpfPlayed != 0L) {
			long jackBit = CardSet.JACK_BITS[trumpfColor.ordinal()];
			if ((trumpfPlayed & jackBit) != 0L) return CardSet.CARDS[Long.numberOfTrailingZeros(jackBit)];
			long nineBit = CardSet.NINE_BITS[trumpfColor.ordinal()];
			if ((trumpfPlayed & nineBit) != 0L) return CardSet.CARDS[Long.numberOfTrailingZeros(nineBit)];
			long normalTrumpf = trumpfPlayed & ~jackBit & ~nineBit;
			return CardSet.CARDS[63 - Long.numberOfLeadingZeros(normalTrumpf)];
		}
		if (roundColor == null) return null;
		long roundBits = playedBits & CardSet.COLOR_MASKS[roundColor.ordinal()];
		return roundBits == 0L ? null : CardSet.CARDS[63 - Long.numberOfLeadingZeros(roundBits)];
	}

	@Override
	public int calculateScore(Set<Card> playedCards) {
		int score = 0;
		for (Card card : playedCards)
			score += getCardScore(card);

		return getFactor() * score;
	}

	private int getCardScore(Card card) {
		if (card.getValue() == CardValue.EIGHT) return 0;
		if (isTrumpf(card)) {
			return card.getValue().getTrumpfScore();
		} else {
			return card.getValue().getScore();
		}
	}


	@Override
	public boolean canPlayCard(Card card, Set<Card> alreadyPlayedCards, Color currentRoundColor, Set<Card> playerCards) {
		return canPlayCard(card, CardSet.toBits(alreadyPlayedCards), currentRoundColor, CardSet.toBits(playerCards));
	}

	@Override
	public boolean canPlayCard(Card card, long alreadyPlayedBits, Color currentRoundColor, long playerCardBits) {
		if (alreadyPlayedBits == 0L) return true;
		long trumpfMask = CardSet.COLOR_MASKS[trumpfColor.ordinal()];
		if ((playerCardBits & ~trumpfMask) == 0L) return true;                 // hasOnlyTrumpf
		long cardBit = 1L << card.ordinal();
		boolean cardIsTrumpf = (cardBit & trumpfMask) != 0L;
		if (cardIsTrumpf && currentRoundColor != trumpfColor)
			return isHighestTrumpf(cardBit, alreadyPlayedBits, trumpfMask);
		if (currentRoundColor == trumpfColor) {
			long jackBit = CardSet.JACK_BITS[trumpfColor.ordinal()];
			if ((playerCardBits & trumpfMask & ~jackBit) == 0L) return true;  // hasOnlyJackOfTrumpf
		}
		if (currentRoundColor == null) return true;
		long roundColorMask = CardSet.COLOR_MASKS[currentRoundColor.ordinal()];
		return (playerCardBits & roundColorMask) == 0L || card.getColor() == currentRoundColor;
	}

	@Override
	public long validCardsBits(long availableBits, long playedBits, Color roundColor) {
		long trumpfMask = CardSet.COLOR_MASKS[trumpfColor.ordinal()];
		long playerNonTrumpf = availableBits & ~trumpfMask;

		if (playedBits == 0L || playerNonTrumpf == 0L) return availableBits;

		long playerTrumpf = availableBits & trumpfMask;

		if (roundColor == trumpfColor) {
			long jackBit = CardSet.JACK_BITS[trumpfColor.ordinal()];
			if ((playerTrumpf & ~jackBit) == 0L) return availableBits;
			return playerTrumpf;
		}

		if (roundColor == null) return availableBits;

		long roundColorBits = availableBits & CardSet.COLOR_MASKS[roundColor.ordinal()];
		if (roundColorBits == 0L) {
			// Void in the led suit: any non-trump may be discarded, but the undertrump
			// restriction still applies to the player's trumps (canPlayCard agrees;
			// returning availableBits here let MCTS search illegal undertrumps).
			return playerNonTrumpf | validStechenTrumpfBits(playerTrumpf, playedBits, trumpfMask);
		}

		return roundColorBits | validStechenTrumpfBits(playerTrumpf, playedBits, trumpfMask);
	}

	private long validStechenTrumpfBits(long playerTrumpf, long playedBits, long trumpfMask) {
		if (playerTrumpf == 0L) return 0L;
		long trumpfPlayed = playedBits & trumpfMask;
		if (trumpfPlayed == 0L) return playerTrumpf;

		long jackBit = CardSet.JACK_BITS[trumpfColor.ordinal()];
		long nineBit = CardSet.NINE_BITS[trumpfColor.ordinal()];

		if ((trumpfPlayed & jackBit) != 0L) return 0L;
		long validJ = playerTrumpf & jackBit;

		if ((trumpfPlayed & nineBit) != 0L) return validJ;

		long validN = playerTrumpf & nineBit;
		long normalPlayed = trumpfPlayed & ~jackBit & ~nineBit;
		long validNorm;
		if (normalPlayed == 0L) {
			validNorm = playerTrumpf & ~jackBit & ~nineBit;
		} else {
			long highestPlayedBit = Long.highestOneBit(normalPlayed);
			validNorm = playerTrumpf & ~jackBit & ~nineBit & -(highestPlayedBit << 1);
		}
		return validJ | validN | validNorm;
	}

	private boolean isHighestTrumpf(long cardBit, long alreadyPlayedBits, long trumpfMask) {
		long trumpfPlayed = alreadyPlayedBits & trumpfMask;
		if (trumpfPlayed == 0L) return true;

		long jackBit = CardSet.JACK_BITS[trumpfColor.ordinal()];
		long nineBit = CardSet.NINE_BITS[trumpfColor.ordinal()];

		if ((trumpfPlayed & jackBit) != 0L) return cardBit == jackBit;
		if (cardBit == jackBit) return true;

		if ((trumpfPlayed & nineBit) != 0L) return cardBit == nineBit;
		if (cardBit == nineBit) return true;

		// Normal rank order within color: higher ordinal = higher rank (for non-J non-9 cards)
		long higherBits = trumpfMask & ~jackBit & ~nineBit & -(cardBit << 1);
		return (trumpfPlayed & higherBits) == 0L;
	}

	@Override
	public int getFactor() {
		return 1;
	}

	@Override
	public Comparator<Card> createRankComparator() {
		return this::compareWithTrumpf;

	}

	@Override
	public String toString() {
		return getTrumpfName() + " - " + getTrumpfColor();
	}

	private boolean isTrumpf(Card card) {
		return card.getColor() == trumpfColor;
	}

	private int compareWithTrumpf(Card card1, Card card2) {
		if (isTrumpf(card1) && isTrumpf(card2)) {
			return compareTrumpf(card1, card2);
		} else if (isTrumpf(card1)) {
			return 1;
		} else if (isTrumpf(card2)) {
			return -1;
		} else {
			return compareMoves(card1, card2);
		}
	}

	private int compareTrumpf(Card first, Card second) {
		return first.isHigherTrumpfThan(second) ? 1 : -1;
	}

	private int compareMoves(Card card1, Card card2) {
		return card1.isHigherThan(card2) ? 1 : -1;
	}

}
