package fr.enimaloc.catapult.service.epic.connect;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One WebSocket connection to Epic Connect (STOMP) for one bot account. A client is single-use: after
 * {@link #close()} or a connection loss, build a new one.
 *
 * <p>The connection URL carries the access token, so it never reaches a log line, an exception message or a cause
 * chain: failures are rewrapped as {@link EpicConnectException} around a cause that holds only an exception type.
 */
@Slf4j
public class EpicConnectClient {

    static final String CONNECTED_TYPE = "core.connect.v1.connected";
    private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    /** Opens a WebSocket on {@code uri} delivering its events to {@code listener}. */
    @FunctionalInterface
    public interface Connector {
        CompletableFuture<WebSocket> open(URI uri, WebSocket.Listener listener);
    }

    private final Connector connector;
    private final URI baseUrl;
    private final Duration heartbeat;
    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile WebSocket socket;
    private volatile EpicConnectListener listener;
    private volatile ScheduledFuture<?> heartbeatTask;

    public EpicConnectClient(Connector connector, URI baseUrl, Duration heartbeat,
                             ScheduledExecutorService scheduler) {
        this.connector = connector;
        this.baseUrl = baseUrl;
        this.heartbeat = heartbeat;
        this.scheduler = scheduler;
    }

    /**
     * Opens the connection, sends CONNECT and SUBSCRIBE and starts the heart-beat.
     *
     * @throws EpicConnectException when the connection cannot be opened or the handshake cannot be sent; the
     *                              connection is closed and the listener is not called
     */
    public void connect(String accessToken, String botAccountId, EpicConnectListener listener) {
        this.listener = listener;
        String base = baseUrl.toString();
        URI uri = URI.create((base.endsWith("/") ? base : base + "/") + "?auth-token="
                + URLEncoder.encode(accessToken, StandardCharsets.UTF_8) + "&accept-language=en-US");
        try {
            WebSocket ws = connector.open(uri, new Reader())
                    .orTimeout(OPEN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
            this.socket = ws;
            send(StompFrames.connect());
            send(StompFrames.subscribe(botAccountId));
            long millis = heartbeat.toMillis();
            heartbeatTask = scheduler.scheduleAtFixedRate(this::sendHeartbeat, millis, millis,
                    TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            closed.set(true);
            abortQuietly();
            throw sanitize(e);
        }
    }

    public void close() {
        shutdown(null);
    }

    private void send(String frame) {
        WebSocket ws = socket;
        if (ws == null || closed.get()) {
            throw new EpicConnectException("Epic Connect connection is closed");
        }
        // The JDK forbids overlapping sends: the heart-beat and the handshake take turns.
        synchronized (this) {
            ws.sendText(frame, true).orTimeout(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
        }
    }

    private void sendHeartbeat() {
        try {
            send(StompFrames.HEARTBEAT);
        } catch (RuntimeException e) {
            shutdown(sanitize(e));
        }
    }

    private void handle(String raw) {
        Optional<StompFrames.Frame> parsed = StompFrames.parse(raw);
        if (parsed.isEmpty()) {
            return;
        }
        StompFrames.Frame frame = parsed.get();
        switch (frame.command()) {
            case "ERROR" -> shutdown(new EpicConnectException(
                    "Epic Connect refused the connection: " + frame.headers().getOrDefault("message", "unknown")));
            case "MESSAGE" -> dispatch(frame);
            default -> log.debug("Ignoring an Epic Connect {} frame", frame.command());
        }
    }

    private void dispatch(StompFrames.Frame frame) {
        Optional<EpicConnectMessage> message = EpicConnectMessage.from(frame);
        if (message.isEmpty() || message.get().type().isEmpty()) {
            log.debug("Dropping an Epic Connect MESSAGE frame with an unknown type or an unparsable body");
            return;
        }
        try {
            if (CONNECTED_TYPE.equals(message.get().type())) {
                listener.onConnected(message.get().connectionId());
            } else {
                listener.onMessage(message.get());
            }
        } catch (RuntimeException e) {
            log.warn("An Epic Connect listener failed on a {} message", message.get().type(), e);
        }
    }

    /** Idempotent. A local {@link #close()} reports a null cause. */
    private void shutdown(Throwable cause) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
        }
        WebSocket ws = socket;
        if (ws != null) {
            if (cause == null) {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(t -> null);
            } else {
                ws.abort();
            }
        }
        EpicConnectListener l = listener;
        if (l != null) {
            try {
                l.onClosed(cause);
            } catch (RuntimeException e) {
                log.warn("An Epic Connect listener failed on close", e);
            }
        }
    }

    private void abortQuietly() {
        WebSocket ws = socket;
        if (ws != null) {
            ws.abort();
        }
    }

    /** Keeps only the failure's type: its message and cause chain may quote the URL, hence the token. */
    private static EpicConnectException sanitize(Throwable failure) {
        Throwable root = failure;
        while ((root instanceof java.util.concurrent.CompletionException
                || root instanceof java.util.concurrent.ExecutionException) && root.getCause() != null) {
            root = root.getCause();
        }
        if (root instanceof EpicConnectException e) {
            return e;
        }
        return new EpicConnectException("Epic Connect connection failed",
                new IOException(root.getClass().getName()));
    }

    /** Reassembles partial text and binary messages (the server may use either) and feeds {@link #handle}. */
    private final class Reader implements WebSocket.Listener {

        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String message = text.toString();
                text.setLength(0);
                handle(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binary.writeBytes(chunk);
            if (last) {
                String message = binary.toString(StandardCharsets.UTF_8);
                binary.reset();
                handle(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            shutdown(new EpicConnectException("Epic Connect closed the connection with status " + statusCode));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            shutdown(sanitize(error));
        }
    }
}
