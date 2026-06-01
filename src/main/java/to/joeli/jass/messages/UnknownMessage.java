package to.joeli.jass.messages;

import com.fasterxml.jackson.annotation.JsonProperty;
import to.joeli.jass.client.websocket.GameHandler;
import to.joeli.jass.messages.responses.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class UnknownMessage implements Message {

    private static final Logger logger = LoggerFactory.getLogger(UnknownMessage.class);

    private final String unkownMessageType;
    private final Object data;

    public UnknownMessage(
            @JsonProperty(value = "type", required = false) String unkownMessageType,
            @JsonProperty(value = "data") Object data) {
        this.unkownMessageType = unkownMessageType;
        this.data = data;
    }

    @Override
    public Optional<Response> dispatch(GameHandler handler) {
        logger.warn("Unknown message: {} data={}", unkownMessageType, data);
        return Optional.empty();
    }
}
