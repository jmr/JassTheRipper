package to.joeli.jass.client.game;

import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.cards.CardSet;
import to.joeli.jass.game.cards.Color;
import to.joeli.jass.game.mode.Mode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class Round {
	private final Mode mode;
	private final int roundNumber;
	private final PlayingOrder playingOrder;
	private final List<Move> moves = new ArrayList<>();
	private long playedCardBits = 0L;
	private Color roundColor = null;

	public static Round createRound(Mode gameMode, int roundNumber, PlayingOrder playingOrder) {
		return new Round(gameMode, roundNumber, playingOrder);
	}

	private Round(Mode mode, int roundNumber, PlayingOrder playingOrder) {
		this.mode = mode;
		this.roundNumber = roundNumber;
		this.playingOrder = playingOrder;
	}

	/**
	 * Copy constructor for deep copy
	 *
	 * @param round
	 */
	public Round(Round round) {
		this.mode = round.getMode();
		this.roundNumber = round.getRoundNumber();
		this.playingOrder = new PlayingOrder(round.getPlayingOrder());
		this.roundColor = round.roundColor;
		for (Move move : round.getMoves()) {
			Move copy = new Move(move);
			this.moves.add(copy);
			this.playedCardBits |= 1L << copy.getPlayedCard().ordinal();
		}
	}

	public void makeMove(Move move) {
		if (!move.getPlayer().equals(playingOrder.getCurrentPlayer()))
			throw new RuntimeException("It's not players " + move.getPlayer() + " turn. It's " + playingOrder.getCurrentPlayer() + " turn.");
		if (moves.size() == 4)
			throw new RuntimeException("Only four cards can be played in a round.");

		if (roundColor == null) roundColor = move.getPlayedCard().getColor();
		moves.add(move);
		playedCardBits |= 1L << move.getPlayedCard().ordinal();
		// NOTE: If this method were called here it would be a bit simpler. But it breaks tests
		// move.getPlayer().onMoveMade(move);
		playingOrder.moveToNextPlayer();
	}

	public Card getCardOfPlayer(Player player) {
		for (Move move : moves) {
			if (move.getPlayer().equals(player))
				return move.getPlayedCard();
		}
		return null;
	}

	public int getRoundNumber() {
		return roundNumber;
	}

	public int calculateScore() {
		return mode.calculateRoundScore(roundNumber, playedCardBits);
	}

	public Card getWinningCard() {
		return mode.determineWinningCard(playedCardBits, getRoundColor());
	}

	public long getPlayedCardBits() {
		return playedCardBits;
	}

	public EnumSet<Card> getPlayedCards() {
		return CardSet.toEnumSet(playedCardBits);
	}

	public List<Card> getPlayedCardsInOrder() {
		List<Card> cards = new ArrayList<>();
		for (Move move : moves)
			cards.add(move.getPlayedCard());
		return cards;
	}

	public Color getRoundColor() {
		return roundColor;
	}

	public Player getWinner() {
		Card winningCard = mode.determineWinningCard(playedCardBits, getRoundColor());
		if (winningCard == null) return null;
		for (Move move : moves) {
			if (winningCard == move.getPlayedCard()) return move.getPlayer();
		}
		return null;
	}

	public boolean hasPlayerAlreadyPlayed(Player player) {
		for (Move move : moves) {
			if (move.getPlayer().equals(player))
				return true;
		}
		return false;
	}

	public Player getCurrentPlayer() {
		return playingOrder.getCurrentPlayer();
	}

	public boolean roundFinished() {
		return getMoves().size() == 4;
	}

	public int numberOfPlayedCards() {
		return Long.bitCount(playedCardBits);
	}

	public List<Move> getMoves() {
		return moves;
	}

	public PlayingOrder getPlayingOrder() {
		return playingOrder;
	}

	public Mode getMode() {
		return mode;
	}

	public boolean isLastRound() {
		return getRoundNumber() == Game.LAST_ROUND_NUMBER;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof Round)) return false;

		Round round = (Round) o;

		if (roundNumber != round.roundNumber) return false;
		if (mode != null ? !mode.equals(round.mode) : round.mode != null) return false;
		if (playingOrder != null ? !playingOrder.equals(round.playingOrder) : round.playingOrder != null) return false;
		return moves != null ? moves.equals(round.moves) : round.moves == null;
	}

	@Override
	public int hashCode() {
		int result = mode != null ? mode.hashCode() : 0;
		result = 31 * result + roundNumber;
		result = 31 * result + (playingOrder != null ? playingOrder.hashCode() : 0);
		result = 31 * result + (moves != null ? moves.hashCode() : 0);
		return result;
	}

	@Override
	public String toString() {
		return "Round{" +
				"mode=" + mode +
				", roundNumber=" + roundNumber +
				", playingOrder=" + playingOrder +
				", moves=" + moves +
				'}';
	}
}
