package to.joeli.jass.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import to.joeli.jass.messages.type.RemotePlayer;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionJoinedData {

    private final List<RemotePlayer> playersInSession;

    public SessionJoinedData(@JsonProperty(value = "playersInSession", required = true) List<RemotePlayer> playersInSession) {
        this.playersInSession = playersInSession;
    }

    public List<RemotePlayer> getPlayersInSession() {
        return playersInSession;
    }
}
