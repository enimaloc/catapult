package fr.enimaloc.catapult.service.twitch;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** EventSubConnections: socket registration, keepalive watchdog, reconnection and hand-over. */
class EventSubConnectionsTest {

    private static final URI CANONICAL = URI.create(EventSubConnections.WS_URL);

    private final OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final HttpClient httpClient = mock(HttpClient.class);
    private final WebSocket.Builder builder = mock(WebSocket.Builder.class);
    private final List<EventSubConnections.Listener> listeners = new CopyOnWriteArrayList<>();
    private final EventSubConnections connections = new EventSubConnections("[Test]", "test-watchdog", tokens,
            (user, token, message, reconnectSession) -> received.add(message + (reconnectSession ? " (reconnect)" : "")));
    private final UserAccount user = new UserAccount();
    private final OAuthToken token = new OAuthToken();

    @BeforeEach
    void setUp() {
        user.setId(UUID.randomUUID());
        connections.httpClient = httpClient;
        when(httpClient.newWebSocketBuilder()).thenReturn(builder);
        when(tokens.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.of(token));
    }

    @AfterEach
    void tearDown() {
        connections.shutdown("test over");
    }

    private void handshakesSucceed() {
        when(builder.buildAsync(any(URI.class), any(WebSocket.Listener.class))).thenAnswer(call -> {
            EventSubConnections.Listener listener = call.getArgument(1);
            listeners.add(listener);
            WebSocket ws = mock(WebSocket.class);
            listener.onOpen(ws);
            return CompletableFuture.completedFuture(ws);
        });
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, WebSocket> registered() {
        return (Map<UUID, WebSocket>) ReflectionTestUtils.getField(connections, "connections");
    }

    private WebSocket socketOf(EventSubConnections.Listener listener) {
        return (WebSocket) ReflectionTestUtils.getField(listener, "webSocket");
    }

    @Test
    void open_registersTheSocketOnTheCanonicalUrl() {
        handshakesSucceed();

        connections.open(user, token);

        verify(builder).buildAsync(eq(CANONICAL), any());
        assertThat(connections.size()).isEqualTo(1);
    }

    @Test
    void onOpen_registersConnectionSynchronously() {
        WebSocket ws = mock(WebSocket.class);

        connections.new Listener(user, token, false).onOpen(ws);

        assertThat(registered()).containsEntry(user.getId(), ws);
        verify(ws).request(1);
    }

    @Test
    void onError_immediatelyAfterOnOpen_stillTriggersReconnectCleanup() {
        // A "Connection reset" right after the handshake: onOpen must have registered the socket
        // synchronously so this compare-and-remove can't lose the race.
        WebSocket ws = mock(WebSocket.class);
        var listener = connections.new Listener(user, token, false);

        listener.onOpen(ws);
        listener.onError(ws, new IOException("Connection reset"));

        assertThat(registered()).doesNotContainKey(user.getId());
    }

    @Test
    void closeOrErrorOfAReplacedSocket_leavesItsSuccessorRegistered() {
        WebSocket stale = mock(WebSocket.class);
        WebSocket fresh = mock(WebSocket.class);
        var staleListener = connections.new Listener(user, token, false);
        registered().put(user.getId(), fresh);

        staleListener.onClose(stale, WebSocket.NORMAL_CLOSURE, "replaced");
        staleListener.onError(stale, new IOException("late"));

        assertThat(registered()).containsEntry(user.getId(), fresh);
    }

    @Test
    void aNewerSession_closesThePreviousSocket() {
        WebSocket first = mock(WebSocket.class);
        WebSocket second = mock(WebSocket.class);

        connections.new Listener(user, token, false).onOpen(first);
        connections.new Listener(user, token, true).onOpen(second);
        connections.new Listener(user, token, true).onOpen(second);

        verify(first).sendClose(WebSocket.NORMAL_CLOSURE, "replaced by newer session");
        verify(second, never()).sendClose(anyInt(), anyString());
        assertThat(registered()).containsEntry(user.getId(), second);
    }

    @Test
    void fragmentedMessages_reachTheHandlerOnceComplete() {
        WebSocket ws = mock(WebSocket.class);
        var listener = connections.new Listener(user, token, true);
        listener.onOpen(ws);

        listener.onText(ws, "{\"a\":", false);
        assertThat(received).isEmpty();
        listener.onText(ws, "1}", true);

        assertThat(received).containsExactly("{\"a\":1} (reconnect)");
    }

    @Test
    void serverClose_reconnectsOnTheCanonicalUrl() {
        handshakesSucceed();
        connections.open(user, token);
        var listener = listeners.getFirst();

        listener.onClose(socketOf(listener), 4000, "going away");

        verify(builder, timeout(3000).times(2)).buildAsync(eq(CANONICAL), any());
    }

    @Test
    void failedHandshakes_areRetried() {
        when(builder.buildAsync(any(URI.class), any(WebSocket.Listener.class)))
                .thenReturn(CompletableFuture.failedFuture(new IOException("refused")));

        connections.open(user, token);

        verify(builder, timeout(3000).times(2)).buildAsync(eq(CANONICAL), any());
        connections.forbidReconnect(user);
    }

    @Test
    void forbiddenReconnects_stayDown_untilAllowedAgain() {
        handshakesSucceed();
        connections.open(user, token);
        var listener = listeners.getFirst();
        connections.forbidReconnect(user);

        listener.onClose(socketOf(listener), 4000, "going away");

        verify(builder, after(1500).times(1)).buildAsync(any(), any());
        connections.allowReconnect(user);
    }

    @Test
    void close_dropsTheSocketAndItsState() {
        handshakesSucceed();
        connections.open(user, token);
        WebSocket ws = socketOf(listeners.getFirst());
        connections.onWelcome(user, 30);

        connections.close(user, "bot disabled");
        connections.close(user, "again");

        verify(ws).sendClose(WebSocket.NORMAL_CLOSURE, "bot disabled");
        assertThat(connections.size()).isZero();
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(connections, "keepaliveTimeoutSeconds")).isEmpty();
    }

    @Test
    void welcome_recordsTheKeepalive_andResetsTheBackoff() {
        @SuppressWarnings("unchecked")
        Map<UUID, Long> backoff = (Map<UUID, Long>) ReflectionTestUtils.getField(connections, "retryDelaySeconds");
        backoff.put(user.getId(), 32L);

        connections.onWelcome(user, 30);

        assertThat(backoff).doesNotContainKey(user.getId());
        @SuppressWarnings("unchecked")
        Map<UUID, Long> keepalives = (Map<UUID, Long>) ReflectionTestUtils.getField(connections, "keepaliveTimeoutSeconds");
        assertThat(keepalives).containsEntry(user.getId(), 30L);
    }

    @Test
    void followReconnect_opensTheGivenUrl_withTheFreshestToken() {
        handshakesSucceed();
        OAuthToken fresh = new OAuthToken();
        when(tokens.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.of(fresh));

        connections.followReconnect(user, token, "wss://eventsub.example/reconnect");

        verify(builder).buildAsync(eq(URI.create("wss://eventsub.example/reconnect")), any());
        assertThat(ReflectionTestUtils.getField(listeners.getFirst(), "token")).isSameAs(fresh);
        assertThat(ReflectionTestUtils.getField(listeners.getFirst(), "reconnectSession")).isEqualTo(true);
    }

    @Test
    void watchdog_whenTheSocketIsCurrent_abortsAndReconnects() {
        handshakesSucceed();
        connections.open(user, token);
        var listener = listeners.getFirst();
        WebSocket ws = socketOf(listener);

        connections.onWatchdogTrigger(user, listener);

        verify(ws).abort();
        verify(builder, timeout(3000).times(2)).buildAsync(eq(CANONICAL), any());
    }

    @Test
    void watchdog_whenTheSocketWasReplaced_isANoOp() {
        WebSocket stale = mock(WebSocket.class);
        WebSocket fresh = mock(WebSocket.class);
        var staleListener = connections.new Listener(user, token, false);
        ReflectionTestUtils.setField(staleListener, "webSocket", stale);
        registered().put(user.getId(), fresh);

        connections.onWatchdogTrigger(user, staleListener);

        verify(stale, never()).abort();
        assertThat(registered()).containsEntry(user.getId(), fresh);
    }

    @Test
    void watchdog_afterAnIntentionalDisconnect_abortsWithoutReconnecting() {
        handshakesSucceed();
        connections.open(user, token);
        var listener = listeners.getFirst();
        connections.forbidReconnect(user);

        connections.onWatchdogTrigger(user, listener);

        verify(socketOf(listener)).abort();
        verify(builder, after(1500).times(1)).buildAsync(any(), any());
    }

    @Test
    void missedKeepalives_triggerTheWatchdog() {
        handshakesSucceed();
        connections.open(user, token);
        var listener = listeners.getFirst();
        WebSocket ws = socketOf(listener);
        connections.onWelcome(user, 0);

        listener.onText(ws, "{}", true);

        verify(ws, timeout(3000)).abort();
    }

    @Test
    void shutdown_closesEverySocketWithoutReconnecting() {
        handshakesSucceed();
        connections.open(user, token);
        var listener = listeners.getFirst();
        WebSocket ws = socketOf(listener);

        connections.shutdown("application shutdown");
        listener.onClose(ws, 1000, "closed");

        verify(ws).sendClose(WebSocket.NORMAL_CLOSURE, "application shutdown");
        verify(builder, after(1500).times(1)).buildAsync(any(), any());
    }

    @Test
    void reconnectAfterAnotherSessionOpened_isSkipped() {
        handshakesSucceed();
        connections.open(user, token);
        var listener = listeners.getFirst();

        listener.onClose(socketOf(listener), 4000, "going away");
        connections.new Listener(user, token, false).onOpen(mock(WebSocket.class));

        verify(builder, after(1500).times(1)).buildAsync(any(), any());
        verify(builder, times(1)).buildAsync(any(), any());
    }
}
