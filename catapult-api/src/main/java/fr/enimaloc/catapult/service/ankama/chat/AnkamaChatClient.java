package fr.enimaloc.catapult.service.ankama.chat;

import fr.enimaloc.catapult.service.ankama.chat.ChatFrames.Outer;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.net.SocketFactory;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One connection to the Ankama launcher chat: handshake, correlated requests, pushed events and heartbeats.
 * A client is single-use: after {@link #close()} or a connection loss, build a new one.
 */
@Slf4j
public class AnkamaChatClient {

    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final SocketFactory sockets;
    private final String host;
    private final int port;
    private final Duration heartbeat;
    private final ScheduledExecutorService scheduler;

    private final Map<Integer, CompletableFuture<ChatFrames.Response>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger correlation = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile ChatListener listener;
    private volatile ScheduledFuture<?> heartbeatTask;

    public AnkamaChatClient(SocketFactory sockets, String host, int port, Duration heartbeat,
                            ScheduledExecutorService scheduler) {
        this.sockets = sockets;
        this.host = host;
        this.port = port;
        this.heartbeat = heartbeat;
        this.scheduler = scheduler;
    }

    /**
     * @throws AnkamaChatAuthException when the server refuses the token
     * @throws IOException             on any other connection or protocol failure; the socket is closed
     */
    public void connect(String chatToken, String deviceId, ChatListener listener) throws IOException {
        this.listener = listener;
        Socket s = sockets.createSocket(host, port);
        try {
            if (s instanceof SSLSocket ssl) {
                // The default parameters verify the chain but not the host name.
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(parameters);
            }
            s.setSoTimeout((int) HANDSHAKE_TIMEOUT.toMillis());
            InputStream in = s.getInputStream();
            OutputStream o = s.getOutputStream();
            ChatFrames.write(o, ChatFrames.KIND_PING, new byte[0]);
            Outer pong = ChatFrames.read(in);
            if (pong.kind() != ChatFrames.KIND_PONG) {
                throw new IOException("Expected a pong during the chat handshake, got kind " + pong.kind());
            }
            Map<String, Object> hello = new LinkedHashMap<>();
            hello.put("token", chatToken);
            hello.put("clientType", 1);
            hello.put("osType", 2);
            hello.put("deviceType", 1);
            hello.put("deviceId", deviceId);
            ChatFrames.write(o, ChatFrames.KIND_DATA, MAPPER.writeValueAsBytes(hello));
            Outer answer = ChatFrames.read(in);
            JsonNode json = MAPPER.readTree(new String(answer.body(), StandardCharsets.UTF_8));
            if (answer.kind() != ChatFrames.KIND_DATA || !json.path("success").asBoolean(false)) {
                throw new AnkamaChatAuthException("The chat server refused the chat token");
            }
            s.setSoTimeout(0);
            this.socket = s;
            this.out = o;
            long millis = heartbeat.toMillis();
            heartbeatTask = scheduler.scheduleAtFixedRate(this::sendHeartbeat, millis, millis,
                    TimeUnit.MILLISECONDS);
            Thread reader = new Thread(() -> readLoop(in), "ankama-chat-" + deviceId);
            reader.setDaemon(true);
            reader.start();
        } catch (IOException | RuntimeException e) {
            closeQuietly(s);
            throw e instanceof IOException io ? io : new IOException("Chat handshake failed", e);
        }
    }

    public CompletableFuture<ChatFrames.Response> request(ChatFrames.Payload payload) {
        int id = correlation.incrementAndGet();
        CompletableFuture<ChatFrames.Response> future = new CompletableFuture<ChatFrames.Response>()
                .orTimeout(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        pending.put(id, future);
        future.whenComplete((r, t) -> pending.remove(id));
        try {
            write(ChatFrames.KIND_DATA, ChatFrames.encode(new ChatFrames.Request(id, payload)));
        } catch (IOException | RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public void close() {
        shutdown(null);
    }

    private void write(int kind, byte[] body) throws IOException {
        OutputStream o = out;
        if (o == null || closed.get()) {
            throw new IOException("The chat connection is closed");
        }
        synchronized (o) {
            ChatFrames.write(o, kind, body);
        }
    }

    private void sendHeartbeat() {
        try {
            write(ChatFrames.KIND_HEARTBEAT, new byte[0]);
        } catch (IOException e) {
            shutdown(e);
            ScheduledFuture<?> task = heartbeatTask;
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    private void readLoop(InputStream in) {
        Throwable cause = null;
        try {
            while (!closed.get()) {
                Outer outer = ChatFrames.read(in);
                if (outer.kind() == ChatFrames.KIND_DATA) {
                    dispatch(ChatFrames.decode(outer.body()));
                }
            }
        } catch (EOFException e) {
            cause = new EOFException("The chat server closed the connection");
        } catch (IOException | RuntimeException e) {
            cause = e;
        }
        shutdown(cause);
    }

    private void dispatch(ChatFrames.Frame frame) throws IOException {
        switch (frame) {
            case ChatFrames.Response response -> {
                CompletableFuture<ChatFrames.Response> future = pending.remove(response.correlationId());
                if (future != null) {
                    future.complete(response);
                }
            }
            case ChatFrames.Event event -> listener.onEvent(ChatMessages.event(event.payload()));
            case ChatFrames.Request ignored -> log.debug("Ignoring a request pushed by the chat server");
        }
    }

    /** Idempotent. A local {@link #close()} reports a null cause; once closed, a read error is not a cause. */
    private void shutdown(Throwable cause) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
        }
        Socket s = socket;
        if (s != null) {
            closeQuietly(s);
        }
        Throwable failure = cause != null ? cause : new IOException("The chat connection is closed");
        pending.values().forEach(f -> f.completeExceptionally(failure));
        pending.clear();
        ChatListener l = listener;
        if (l != null && s != null) {
            try {
                l.onClosed(cause);
            } catch (RuntimeException e) {
                log.warn("Chat listener failed in onClosed", e);
            }
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing more to do
        }
    }
}
