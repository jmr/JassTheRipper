package to.joeli.jass.client.websocket;

import to.joeli.jass.messages.Message;
import to.joeli.jass.messages.responses.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Threading contract: GameHandler is NOT thread-safe. All message dispatch must
 * be serialized AND processed in the order messages arrive from the server.
 *
 * Why order matters: Jetty's EPC model can dispatch incoming messages on different
 * worker threads when a callback is blocked (e.g., during MCTS). Java's intrinsic
 * monitor (synchronized) prevents concurrent execution but does NOT guarantee FIFO
 * ordering — a newly unblocked thread can barge ahead of threads already waiting.
 * This means REQUEST_CARD can be processed before the PLAYED_CARDS messages that
 * arrived before it, so the game state seen during MCTS is stale (no cards played,
 * roundColor=null). The chosen card is then invalid in the actual round state.
 *
 * Fix: RemoteGameSocket funnels all Jetty callbacks through a single-threaded
 * queue (see RemoteGameSocket.onWebSocketMessage). GameSocket.onMessage remains
 * synchronous for use in unit tests, which drive the socket directly.
 */
public class GameSocket {

    private final GameHandler handler;
    protected ResponseChannel responseChannel;

    final Logger logger = LoggerFactory.getLogger(getClass());

    public GameSocket(GameHandler handler) {
        this.handler = handler;
    }

    public void onMessage(Message msg) {
        Optional<Response> response = dispatchMessage(msg);
        response.ifPresent(responseChannel::respond);
    }

    public Optional<Response> dispatchMessage(Message msg) {
        return msg.dispatch(handler);
    }

    void onClose(int statusCode, String reason) {
        logger.trace("Connection closed: {} - {}", statusCode, reason);
    }

    public void onConnect(ResponseChannel responseChannel) {
        this.responseChannel = responseChannel;
    }
}
