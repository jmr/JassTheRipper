package to.joeli.jass.messages;

import to.joeli.jass.client.websocket.GameHandler;
import to.joeli.jass.messages.responses.Response;

import java.util.Optional;

public class RequestCard implements Message {

    @Override
    public Optional<Response> dispatch(GameHandler handler) {
        // ofNullable: onRequestCard returns null when the advisor hasn't received full game state yet
        return Optional.ofNullable(handler.onRequestCard());
    }
}
