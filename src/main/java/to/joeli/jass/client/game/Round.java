package to.joeli.jass.client.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import to.joeli.jass.game.cards.Card;
import to.joeli.jass.game.cards.CardSet;
import to.joeli.jass.game.cards.Color;
import to.joeli.jass.game.mode.Mode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class Round {
	private static final Logger logger = LoggerFactory.getLogger(Round.class);

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
		checkInvariants("Round copy ctor");
	}

	public void makeMove(Move move) {
		if (!move.getPlayer().equals(playingOrder.getCurrentPlayer()))
			throw new RuntimeException("It's not players " + move.getPlayer() + " turn. It's " + playingOrder.getCurrentPlayer() + " turn.");
		if (moves.size() == 4)
			throw new RuntimeException("Only four cards can be played in a round.");

		if (roundColor == null) roundColor = move.getPlayedCard().getColor();
		moves.add(move);
		playedCardBits |= 1L << move.getPlayedCard().ordinal();
		checkInvariants("Round.makeMove");
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

	private void checkInvariants(String context) {
		// playedCardBits and moves must encode the same set of cards. If they diverge,
		// Round.getWinner can produce a null winner (winning card not found in moves),
		// which silently falls back to index 0 in PlayingOrder.createOrderStartingFromPlayer.
		int bitCount = Long.bitCount(playedCardBits);
		if (bitCount != moves.size()) {
			throw new IllegalStateException(String.format(
					"INVARIANT VIOLATED in %s: Long.bitCount(playedCardBits)=%d, moves.size()=%d. " +
							"playedCards via bits=%s, moves cards=[%s], moves by player=[%s], " +
							"playingOrder hands=[%s]",
					context, bitCount, moves.size(),
					CardSet.toEnumSet(playedCardBits),
					moves.stream().map(m -> m.getPlayedCard().toString())
							.reduce((a, b) -> a + ", " + b).orElse(""),
					moves.stream().map(m -> m.getPlayer().getName() + "(seat=" + m.getPlayer().getSeatId() + ")->" + m.getPlayedCard())
							.reduce((a, b) -> a + ", " + b).orElse(""),
					playingOrder.getPlayersInInitialOrder().stream()
							.map(p -> p.getName() + "(seat=" + p.getSeatId() + "):" + p.getCards())
							.reduce((a, b) -> a + ", " + b).orElse("")));
		}
	}

	public Player getWinner() {
		Card winningCard = mode.determineWinningCard(playedCardBits, getRoundColor());
		if (winningCard == null) {
			logger.warn("DESYNC: Round.getWinner — determineWinningCard returned null. " +
							"mode={}, roundNumber={}, roundColor={}, playedCardBits={}, moves.size()={}",
					mode, roundNumber, roundColor, playedCardBits, moves.size());
			return null;
		}
		for (Move move : moves) {
			if (winningCard == move.getPlayedCard()) return move.getPlayer();
		}
		logger.warn("DESYNC: Round.getWinner — winning card {} not found in moves. " +
						"This indicates playedCardBits / moves divergence. " +
						"mode={}, roundNumber={}, roundColor={}, playedCardBits bitcount={}, moves.size()={}, " +
						"playedCards via bits={}, moves cards=[{}]",
				winningCard, mode, roundNumber, roundColor,
				Long.bitCount(playedCardBits), moves.size(),
				CardSet.toEnumSet(playedCardBits),
				moves.stream().map(m -> m.getPlayedCard().toString()).collect(java.util.stream.Collectors.joining(", ")));
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
