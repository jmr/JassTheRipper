package to.joeli.jass.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import to.joeli.jass.messages.type.RemoteCard;
import to.joeli.jass.messages.type.RemotePlayer;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionJoinedData {

    private final List<RemotePlayer> playersInSession;
    private final GameStateReplay gameState;

    public SessionJoinedData(
            @JsonProperty(value = "playersInSession", required = true) List<RemotePlayer> playersInSession,
            @JsonProperty("gameState") GameStateReplay gameState) {
        this.playersInSession = playersInSession;
        this.gameState = gameState;
    }

    public List<RemotePlayer> getPlayersInSession() {
        return playersInSession;
    }

    public GameStateReplay getGameState() {
        return gameState;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GameStateReplay {
        private final int gameStartingSeatId;
        private final int currentRound;
        private final int trickLeaderSeatId;
        private final List<RemoteCard> trickPlayedCards;

        public GameStateReplay(
                @JsonProperty(value = "gameStartingSeatId", required = true) int gameStartingSeatId,
                @JsonProperty(value = "currentRound", required = true) int currentRound,
                @JsonProperty(value = "trickLeaderSeatId", required = true) int trickLeaderSeatId,
                @JsonProperty(value = "trickPlayedCards", required = true) List<RemoteCard> trickPlayedCards) {
            this.gameStartingSeatId = gameStartingSeatId;
            this.currentRound = currentRound;
            this.trickLeaderSeatId = trickLeaderSeatId;
            this.trickPlayedCards = trickPlayedCards;
        }

        public int getGameStartingSeatId() { return gameStartingSeatId; }
        public int getCurrentRound() { return currentRound; }
        public int getTrickLeaderSeatId() { return trickLeaderSeatId; }
        public List<RemoteCard> getTrickPlayedCards() { return trickPlayedCards; }
    }
}
