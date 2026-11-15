package fr.enimaloc.catapult.service.epic;

import fr.enimaloc.catapult.service.epic.connect.EpicConnectClient;
import fr.enimaloc.catapult.service.epic.connect.EpicConnectException;
import fr.enimaloc.catapult.service.epic.connect.EpicConnectListener;
import fr.enimaloc.catapult.service.epic.connect.EpicConnectMessage;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;

/** An in-memory {@link EpicConnectClient}: no socket. Lets the test confirm the connection, push messages and drop it. */
class FakeEpicConnectClient extends EpicConnectClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    volatile String accessToken;
    volatile String botAccountId;
    volatile EpicConnectListener listener;
    volatile boolean closed;
    /** Thrown by {@link #connect}, when set. */
    EpicConnectException connectFailure;

    FakeEpicConnectClient() {
        super((uri, listener) -> {
            throw new UnsupportedOperationException("no socket in tests");
        }, URI.create("wss://connect.test"), Duration.ofSeconds(30), null);
    }

    @Override
    public void connect(String accessToken, String botAccountId, EpicConnectListener listener) {
        this.accessToken = accessToken;
        this.botAccountId = botAccountId;
        if (connectFailure != null) {
            throw connectFailure;
        }
        this.listener = listener;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (listener != null) {
            listener.onClosed(null);
        }
    }

    /** The server's {@code core.connect.v1.connected} message. */
    void connected(String connectionId) {
        listener.onConnected(connectionId);
    }

    void push(String type, String payloadJson) {
        listener.onMessage(new EpicConnectMessage(type, "", MAPPER.readTree(payloadJson)));
    }

    /** The server ends the connection. */
    synchronized void drop() {
        closed = true;
        listener.onClosed(new EpicConnectException("Epic Connect closed the connection with status 1006"));
    }
}
