package to.joeli.jass.game.mode;

import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.cards.Color;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

class GeneralRules {

	private static final int LAST_ROUND_BONUS = 5;

	public static int calculateLastRoundBonus(int factor) {
		return factor * LAST_ROUND_BONUS;
	}

	public static boolean canPlayCard(Card card, Set<Card> alreadyPlayedCards, Color currentRoundColor, Set<Card> playerCards) {
		return alreadyPlayedCards.isEmpty()
				|| card.getColor() == currentRoundColor
				|| playerCards.stream().noneMatch(playersCard -> playersCard.getColor() == currentRoundColor);
	}


	public static Card determineWinnerCard(List<Card> cards, Comparator<Card> cardRankComparator, Color trumpfColor) {
		if (cards == null || cards.isEmpty()) return null;
		final Color firstCardColor = cards.get(0).getColor();
		Card winner = null;
		for (Card card : cards) {
			Color c = card.getColor();
			if (c == firstCardColor || c == trumpfColor) {
				if (winner == null || cardRankComparator.compare(card, winner) > 0)
					winner = card;
			}
		}
		return winner;
	}
}
