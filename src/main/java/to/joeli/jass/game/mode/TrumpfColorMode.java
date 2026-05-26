package to.joeli.jass.game.mode;

import to.joeli.jass.client.game.Game;
import to.joeli.jass.client.game.Round;
import to.joeli.jass.game.Trumpf;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.cards.CardValue;
import to.joeli.jass.game.cards.Color;

import java.util.Comparator;
import java.util.Optional;
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
		if (alreadyPlayedCards.isEmpty()) return true;
		if (hasOnlyTrumpf(playerCards)) return true;
		if (isTrumpf(card) && currentRoundColor != trumpfColor) return isHighestTrumpf(card, alreadyPlayedCards);
		if (currentRoundColor == trumpfColor && hasOnlyJackOfTrumpf(playerCards)) return true;
		return !hasOtherCardsOfRoundColor(currentRoundColor, playerCards) || card.getColor() == currentRoundColor;
	}

	private boolean hasOtherCardsOfRoundColor(Color currentRoundColor, Set<Card> playerCards) {
		for (Card playersCard : playerCards)
			if (playersCard.getColor() == currentRoundColor)
				return true;
		return false;
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

	private boolean hasOnlyJackOfTrumpf(Set<Card> playerCards) {
		for (Card cards : playerCards)
			if (cards.getColor() == trumpfColor)
				if (cards.getValue() != CardValue.JACK)
					return false;
		return true;
	}

	private boolean hasOnlyTrumpf(Set<Card> cards) {
		for (Card card : cards)
			if (card.getColor() != trumpfColor)
				return false;
		return true;
	}

	private boolean isHighestTrumpf(Card card, Set<Card> alreadyPlayedCards) {
		if (Round.LEGACY_PLAYOUT) {
			Optional<Card> highest = alreadyPlayedCards.stream()
					.filter(c -> c.getColor() == trumpfColor)
					.max(this::compareTrumpf);
			return !highest.isPresent() || compareTrumpf(card, highest.get()) >= 0;
		}
		for (Card played : alreadyPlayedCards)
			if (played.getColor() == trumpfColor && compareTrumpf(played, card) > 0)
				return false;
		return true;
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
