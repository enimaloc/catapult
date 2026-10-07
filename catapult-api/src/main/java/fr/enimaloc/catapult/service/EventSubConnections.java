package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The WebSocket side of a Twitch EventSub client: one socket per user, a keepalive watchdog,
 * reconnection with exponential backoff and the {@code session_reconnect} hand-over. What the
 * messages mean is left to the owning service, through {@link MessageHandler}.
 */
@Slf4j
final class EventSubConnections {

    static final String WS_URL = "wss://eventsub.wss.twitch.tv/ws";
    static final long DEFAULT_KEEPALIVE_SECONDS = 10L;
    private static final long MAX_RETRY_SECONDS = 60L;
    private static final double KEEPALIVE_GRACE = 1.5;

    /** Handles one complete text message received on a user's socket. */
    @FunctionalInterface
    interface MessageHandler {
        void onMessage(UserAccount user, OAuthToken token, String message, boolean reconnectSession);
    }

    private final String logPrefix;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final MessageHandler handler;

    private final Map<UUID, WebSocket> connections = new ConcurrentHashMap<>();
    private final Set<UUID> intentionallyDisconnected = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> keepaliveTimeoutSeconds = new ConcurrentHashMap<>();
    private final Map<UUID, Long> retryDelaySeconds = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> watchdogs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdogExecutor;
    /** Opens the sockets (tests swap in a mock). */
    HttpClient httpClient = HttpClient.newHttpClient();

    EventSubConnections(String logPrefix, String watchdogThreadName,
                        OAuthTokenRepository oAuthTokenRepository, MessageHandler handler) {
        this.logPrefix = logPrefix;
        this.oAuthTokenRepository = oAuthTokenRepository;
        this.handler = handler;
        this.watchdogExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, watchdogThreadName);
            t.setDaemon(true);
            return t;
        });
    }

    int size() {
        return connections.size();
    }

    /** Opens a brand-new session for the user on the canonical EventSub URL. */
    void open(UserAccount user, OAuthToken token) {
        openConnection(user, token, WS_URL, false);
    }

    /**
     * Follows a {@code session_reconnect}: opens {@code reconnectUrl} (with the user's freshest
     * token), whose welcome carries the subscriptions over and replaces the current socket.
     */
    void followReconnect(UserAccount user, OAuthToken token, String reconnectUrl) {
        openConnection(user, freshToken(user, token), reconnectUrl, true);
    }

    /** Records a {@code session_welcome}: its keepalive deadline, and a healthy connection. */
    void onWelcome(UserAccount user, long keepaliveSeconds) {
        keepaliveTimeoutSeconds.put(user.getId(), keepaliveSeconds);
        // A live session proves the connection is healthy — reset the backoff so a
        // later, unrelated disconnect doesn't inherit delays from a past failure streak.
        retryDelaySeconds.remove(user.getId());
    }

    /** Stops dropped sockets of this user from being reopened, until {@link #allowReconnect}. */
    void forbidReconnect(UserAccount user) {
        intentionallyDisconnected.add(user.getId());
    }

    void allowReconnect(UserAccount user) {
        intentionallyDisconnected.remove(user.getId());
    }

    /** Closes the user's socket and forgets its watchdog, keepalive and backoff state. */
    void close(UserAccount user, String reason) {
        cancelWatchdog(user.getId());
        keepaliveTimeoutSeconds.remove(user.getId());
        retryDelaySeconds.remove(user.getId());
        WebSocket ws = connections.remove(user.getId());
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, reason);
    }

    void shutdown(String reason) {
        watchdogs.values().forEach(f -> f.cancel(false));
        watchdogs.clear();
        watchdogExecutor.shutdownNow();
        // De-register before closing so onClose sees a foreign socket and never reconnects
        connections.keySet().forEach(id -> {
            WebSocket ws = connections.remove(id);
            if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, reason);
        });
    }

    private void openConnection(UserAccount user, OAuthToken token, String wsUrl, boolean reconnectSession) {
        // Registration happens synchronously in Listener.onOpen, not here: this
        // CompletableFuture completes asynchronously and can lag behind onOpen, leaving a
        // window where an immediate onError/onClose (e.g. "Connection reset" right after the
        // handshake) would find no matching entry in `connections` and silently drop the
        // reconnect — registering here was the root cause of connections never recovering.
        httpClient.newWebSocketBuilder()
            .buildAsync(URI.create(wsUrl), new Listener(user, token, reconnectSession))
            .whenComplete((ws, ex) -> {
                if (ex != null) {
                    log.warn("{} Failed to connect for user {}: {}", logPrefix, user.getId(), ex.getMessage());
                    scheduleReconnect(user, token);
                }
            });
    }

    private void scheduleReconnect(UserAccount user, OAuthToken token) {
        long delaySeconds = retryDelaySeconds.getOrDefault(user.getId(), 1L);
        retryDelaySeconds.put(user.getId(), Math.min(delaySeconds * 2, MAX_RETRY_SECONDS));
        CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS).execute(() -> {
            if (connections.containsKey(user.getId())) return;
            if (intentionallyDisconnected.contains(user.getId())) return;
            // Always a brand-new session on the canonical URL: reconnect URLs are single-use
            openConnection(user, freshToken(user, token), WS_URL, false);
        });
    }

    private OAuthToken freshToken(UserAccount user, OAuthToken fallback) {
        return oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH).orElse(fallback);
    }

    private void armWatchdog(UserAccount user, Listener listener) {
        long timeout = keepaliveTimeoutSeconds.getOrDefault(user.getId(), DEFAULT_KEEPALIVE_SECONDS);
        long deadlineSeconds = Math.max(1L, Math.round(timeout * KEEPALIVE_GRACE));
        ScheduledFuture<?> next = watchdogExecutor.schedule(
            () -> onWatchdogTrigger(user, listener), deadlineSeconds, TimeUnit.SECONDS);
        ScheduledFuture<?> previous = watchdogs.put(user.getId(), next);
        if (previous != null) previous.cancel(false);
    }

    private void cancelWatchdog(UUID userId) {
        ScheduledFuture<?> f = watchdogs.remove(userId);
        if (f != null) f.cancel(false);
    }

    /**
     * No message (event or keepalive) arrived within the keepalive deadline: Twitch's protocol
     * says the socket is dead. Idempotent — a session_reconnect may already have replaced this
     * socket, which is then left alone. {@link WebSocket#abort()} never triggers {@code onClose},
     * so the cleanup happens here.
     */
    void onWatchdogTrigger(UserAccount user, Listener listener) {
        if (!connections.remove(user.getId(), listener.webSocket)) return;
        log.warn("{} keepalive timeout for user {}, aborting and reconnecting", logPrefix, user.getId());
        listener.webSocket.abort();
        if (intentionallyDisconnected.contains(user.getId())) return;
        scheduleReconnect(user, listener.token);
    }

    /** Drops a socket the server closed or broke — unless it was already replaced — and reconnects. */
    private void onLost(UserAccount user, OAuthToken token, WebSocket webSocket) {
        if (!connections.remove(user.getId(), webSocket)) return;
        cancelWatchdog(user.getId());
        // Self-initiated closes de-register before closing, so reaching this point
        // means the server dropped us — reconnect regardless of the status code.
        if (!intentionallyDisconnected.contains(user.getId())) {
            scheduleReconnect(user, token);
        }
    }

    class Listener implements WebSocket.Listener {
        private final UserAccount user;
        private final OAuthToken token;
        private final boolean reconnectSession;
        private final StringBuilder buffer = new StringBuilder();
        // Written once by the WS callback thread in onOpen, then only read by the watchdog scheduler.
        @SuppressWarnings("java:S3077")
        private volatile WebSocket webSocket;

        Listener(UserAccount user, OAuthToken token, boolean reconnectSession) {
            this.user = user;
            this.token = token;
            this.reconnectSession = reconnectSession;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            log.debug("{} WebSocket opened for user {}", logPrefix, user.getId());
            this.webSocket = webSocket;
            // A session_reconnect replaces the previous socket: register the new one first,
            // then close the old — its onClose sees a foreign socket and no-ops. Done here,
            // synchronously with the handshake, so no error on this socket can race ahead of
            // its own registration (see openConnection).
            WebSocket previous = connections.put(user.getId(), webSocket);
            if (previous != null && previous != webSocket) {
                previous.sendClose(WebSocket.NORMAL_CLOSURE, "replaced by newer session");
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                handler.onMessage(user, token, message, reconnectSession);
                armWatchdog(user, this);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.debug("{} WebSocket closed for user {} ({}): {}", logPrefix, user.getId(), statusCode, reason);
            onLost(user, token, webSocket);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("{} WebSocket error for user {}: {}", logPrefix, user.getId(), error.getMessage());
            onLost(user, token, webSocket);
        }
    }
}
