package to.joeli.jass.client.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PlayingOrder {

	private final List<Player> playersInInitialPlayingOrder;
	private final int startingPlayerIndex;
	private int currentPlayerIndex;

	public static PlayingOrder createOrder(List<Player> playersInPlayingOrder) {
		return new PlayingOrder(playersInPlayingOrder, 0);
	}

	public static PlayingOrder createOrderStartingFromPlayer(List<Player> playersInPlayingOrder, Player startFrom) {
		if (startFrom == null) return new PlayingOrder(playersInPlayingOrder, 0);
		for (int i = 0; i < playersInPlayingOrder.size(); i++)
			if (playersInPlayingOrder.get(i).equals(startFrom))
				return new PlayingOrder(playersInPlayingOrder, i);
		return new PlayingOrder(playersInPlayingOrder, 0);
	}

	private PlayingOrder(List<Player> playersInPlayingOrder, int startingPlayerIndex) {
		this.playersInInitialPlayingOrder = playersInPlayingOrder;
		this.startingPlayerIndex = startingPlayerIndex;
		this.currentPlayerIndex = 0;
	}

	/**
	 * Copy constructor for deep copy
	 *
	 * @param playingOrder
	 */
	public PlayingOrder(PlayingOrder playingOrder) {
		this.playersInInitialPlayingOrder = new ArrayList<>();
		for (Player player : playingOrder.playersInInitialPlayingOrder)
			this.playersInInitialPlayingOrder.add(new Player(player));
		this.startingPlayerIndex = playingOrder.startingPlayerIndex;
		this.currentPlayerIndex = playingOrder.currentPlayerIndex;
	}

	public List<Player> getPlayersInInitialOrder() {
		return playersInInitialPlayingOrder;
	}

	public List<Player> getPlayersInCurrentOrder() {
		List<Player> playersInCurrentPlayingOrder = new ArrayList<>();
		for (int i = currentPlayerIndex; i < 4; i++) {
			playersInCurrentPlayingOrder.add(getPlayerByIndex(i));
		}
		for (int i = 0; i < currentPlayerIndex; i++) {
			playersInCurrentPlayingOrder.add(getPlayerByIndex(i));
		}
		return playersInCurrentPlayingOrder;
	}

	public Player getCurrentPlayer() {
		return getPlayerByIndex(currentPlayerIndex);
	}

	public Player getNextPlayer() {
		return getPlayerByIndex(currentPlayerIndex + 1);
	}

	public Player getPartnerOfPlayer(Player player) {
		return playersInInitialPlayingOrder.get((player.getSeatId() + 2) & 3);
	}

	public void moveToNextPlayer() {
		currentPlayerIndex++;
	}

	private Player getPlayerByIndex(int index) {
		return playersInInitialPlayingOrder.get(getBoundIndex(index));
	}

	private int getBoundIndex(int playerPosition) {
		return (this.startingPlayerIndex + playerPosition) & 3;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof PlayingOrder)) return false;

		PlayingOrder that = (PlayingOrder) o;

		if (startingPlayerIndex != that.startingPlayerIndex) return false;
		if (currentPlayerIndex != that.currentPlayerIndex) return false;
		return Objects.equals(this.playersInInitialPlayingOrder, that.playersInInitialPlayingOrder);
	}

	@Override
	public int hashCode() {
		int result = playersInInitialPlayingOrder != null ? playersInInitialPlayingOrder.hashCode() : 0;
		result = 31 * result + startingPlayerIndex;
		result = 31 * result + currentPlayerIndex;
		return result;
	}

	@Override
	public String toString() {
		return "PlayingOrder{" +
				"playersInInitialPlayingOrder=" + playersInInitialPlayingOrder +
				", startingPlayerIndex=" + startingPlayerIndex +
				", currentPlayerIndex=" + currentPlayerIndex +
				'}';
	}
}
