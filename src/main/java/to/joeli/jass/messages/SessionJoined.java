package to.joeli.jass.messages;

import com.fasterxml.jackson.annotation.JsonProperty;
import to.joeli.jass.client.websocket.GameHandler;
import to.joeli.jass.messages.responses.Response;

import java.util.Optional;

public class SessionJoined implements Message {

    private final SessionJoinedData data;

    public SessionJoined(@JsonProperty(value = "data", required = true) SessionJoinedData data) {
        this.data = data;
    }

    @Override
    public Optional<Response> dispatch(GameHandler handler) {
        handler.onSessionJoined(data);
        return Optional.empty();
    }
}
