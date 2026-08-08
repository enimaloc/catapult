package fr.enimaloc.catapult.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.StreamOfflineEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.notification.CatapultCategoryChangeStateService;
import fr.enimaloc.catapult.service.notification.TwitchatNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TwitchEventSubServiceTest {

    @Mock private OAuthTokenRepository oAuthTokenRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private TwitchTokenService twitchTokenService;
    @Mock private StreamStateService streamStateService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RestClient restClient;
    @Mock private TwitchatNotifier twitchatNotifier;
    @Mock private CatapultCategoryChangeStateService categoryChangeStateService;

    @Mock private RestClient.RequestBodyUriSpec postUriSpec;
    @Mock private RestClient.RequestBodySpec postBodySpec;
    @Mock private RestClient.ResponseSpec postResponseSpec;

    @Mock private RestClient.RequestHeadersUriSpec getUriSpec;
    @Mock private RestClient.RequestHeadersSpec getHeaderSpec;
    @Mock private RestClient.ResponseSpec getResponseSpec;

    @InjectMocks private TwitchEventSubService service;

    private final ObjectMapper mapper = new ObjectMapper();
    private UserAccount user;
    private OAuthToken token;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("broadcaster-123");

        token = new OAuthToken();
        token.setAccessToken("encrypted");

        when(twitchTokenService.resolveAccessToken(eq(token), eq(user))).thenReturn("decrypted-token");
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.of(token));

        when(restClient.post()).thenReturn(postUriSpec);
        when(postUriSpec.uri(anyString())).thenReturn(postBodySpec);
        when(postBodySpec.header(anyString(), anyString())).thenReturn(postBodySpec);
        when(postBodySpec.body(any())).thenReturn(postBodySpec);
        when(postBodySpec.retrieve()).thenReturn(postResponseSpec);
        when(postResponseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());

        when(userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE))
            .thenReturn(List.of());

        ReflectionTestUtils.setField(service, "twitchClientId", "test-client-id");
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
    }

    @Test
    void handleMessage_streamOnline_setsLiveTrueAndPublishesEvent() {
        String message = """
            {
              "metadata": { "message_type": "notification", "subscription_type": "stream.online" },
              "payload": { "event": {} }
            }
            """;

        service.handleMessage(user, token, message, false);

        verify(streamStateService).setLive(user, true);
        ArgumentCaptor<StreamOnlineEvent> captor = ArgumentCaptor.forClass(StreamOnlineEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(user);
    }

    @Test
    void handleMessage_streamOffline_setsLiveFalseAndPublishesEvent() {
        String message = """
            {
              "metadata": { "message_type": "notification", "subscription_type": "stream.offline" },
              "payload": { "event": {} }
            }
            """;

        service.handleMessage(user, token, message, false);

        verify(streamStateService).setLive(user, false);
        ArgumentCaptor<StreamOfflineEvent> captor = ArgumentCaptor.forClass(StreamOfflineEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(user);
    }

    @Test
    void handleChannelUpdate_matchesSelfSetState_notifiesCatapultChange() {
        when(categoryChangeStateService.matchesCatapultChange(user, "222")).thenReturn(Optional.of("111"));

        String firstMessage = """
            {
              "metadata": { "message_type": "notification", "subscription_type": "channel.update" },
              "payload": { "event": { "category_id": "111", "category_name": "Old Game", "content_classification_labels": [] } }
            }
            """;
        String secondMessage = """
            {
              "metadata": { "message_type": "notification", "subscription_type": "channel.update" },
              "payload": { "event": { "category_id": "222", "category_name": "New Game", "content_classification_labels": [] } }
            }
            """;

        service.handleMessage(user, token, firstMessage, false);
        service.handleMessage(user, token, secondMessage, false);

        verify(twitchatNotifier).onCategoryChangedByCatapult(user, "222", "New Game", "111");
        verify(twitchatNotifier, never()).onCategoryChangedManually(any(), any(), any());
    }

    @Test
    void handleChannelUpdate_noSelfSetMatch_notifiesManualChange() {
        when(categoryChangeStateService.matchesCatapultChange(user, "222")).thenReturn(Optional.empty());

        String firstMessage = """
            {
              "metadata": { "message_type": "notification", "subscription_type": "channel.update" },
              "payload": { "event": { "category_id": "111", "category_name": "Old Game", "content_classification_labels": [] } }
            }
            """;
        String secondMessage = """
            {
              "metadata": { "message_type": "notification", "subscription_type": "channel.update" },
              "payload": { "event": { "category_id": "222", "category_name": "New Game", "content_classification_labels": [] } }
            }
            """;

        service.handleMessage(user, token, firstMessage, false);
        service.handleMessage(user, token, secondMessage, false);

        verify(twitchatNotifier).onCategoryChangedManually(user, "222", "New Game");
        verify(twitchatNotifier, never()).onCategoryChangedByCatapult(any(), any(), any(), any());
    }

    @Test
    void handleMessage_sessionWelcome_subscribesToStreamOnlineAndOffline() {
        String message = """
            {
              "metadata": { "message_type": "session_welcome" },
              "payload": { "session": { "id": "session-abc" } }
            }
            """;

        service.handleMessage(user, token, message, false);

        // One POST each for stream.online, stream.offline, channel.update
        verify(restClient, times(3)).post();
    }

    @Test
    void handleMessage_sessionWelcome_whenStreamIsLive_setsLiveTrue() {
        when(restClient.get()).thenReturn(getUriSpec);
        when(getUriSpec.uri(anyString())).thenReturn(getHeaderSpec);
        when(getHeaderSpec.header(anyString(), anyString())).thenReturn(getHeaderSpec);
        when(getHeaderSpec.retrieve()).thenReturn(getResponseSpec);
        // first call is initChannelState (empty object, returns early), second is initStreamState
        when(getResponseSpec.body(String.class))
            .thenReturn("{}")
            .thenReturn("{\"data\":[{\"id\":\"42\",\"user_id\":\"broadcaster-123\"}]}");

        String message = """
            {
              "metadata": { "message_type": "session_welcome" },
              "payload": { "session": { "id": "session-abc" } }
            }
            """;

        service.handleMessage(user, token, message, false);

        verify(streamStateService).setLive(user, true);
    }

    @Test
    void handleMessage_sessionWelcome_whenStreamIsOffline_setsLiveFalse() {
        when(restClient.get()).thenReturn(getUriSpec);
        when(getUriSpec.uri(anyString())).thenReturn(getHeaderSpec);
        when(getHeaderSpec.header(anyString(), anyString())).thenReturn(getHeaderSpec);
        when(getHeaderSpec.retrieve()).thenReturn(getResponseSpec);
        when(getResponseSpec.body(String.class))
            .thenReturn("{}")
            .thenReturn("{\"data\":[]}");

        String message = """
            {
              "metadata": { "message_type": "session_welcome" },
              "payload": { "session": { "id": "session-abc" } }
            }
            """;

        service.handleMessage(user, token, message, false);

        verify(streamStateService).setLive(user, false);
    }

    @Test
    void handleMessage_sessionWelcome_whenStreamsApiFails_doesNotThrow() {
        when(restClient.get()).thenReturn(getUriSpec);
        when(getUriSpec.uri(anyString())).thenReturn(getHeaderSpec);
        when(getHeaderSpec.header(anyString(), anyString())).thenReturn(getHeaderSpec);
        when(getHeaderSpec.retrieve()).thenReturn(getResponseSpec);
        when(getResponseSpec.body(String.class))
            .thenReturn("{}")
            .thenThrow(new RuntimeException("network error"));

        String message = """
            {
              "metadata": { "message_type": "session_welcome" },
              "payload": { "session": { "id": "session-abc" } }
            }
            """;

        assertThatNoException().isThrownBy(() -> service.handleMessage(user, token, message, false));
        verify(streamStateService, never()).setLive(any(), anyBoolean());
    }

    @Test
    void handleMessage_invalidJson_doesNotThrow() {
        assertThatNoException().isThrownBy(
            () -> service.handleMessage(user, token, "not-json", false)
        );
    }

    @Test
    void handleMessage_unknownMessageType_doesNotThrow() {
        String message = """
            { "metadata": { "message_type": "session_keepalive" }, "payload": {} }
            """;

        assertThatNoException().isThrownBy(
            () -> service.handleMessage(user, token, message, false)
        );
    }

    @Test
    void handleMessage_sessionWelcome_storesKeepaliveTimeoutFromPayload() {
        String message = """
            {
              "metadata": { "message_type": "session_welcome" },
              "payload": { "session": { "id": "session-abc", "keepalive_timeout_seconds": 30 } }
            }
            """;

        service.handleMessage(user, token, message, false);

        Map<UUID, Long> timeouts = (Map<UUID, Long>) ReflectionTestUtils.getField(service, "keepaliveTimeoutSeconds");
        assertThat(timeouts).containsEntry(user.getId(), 30L);
    }

    @Test
    void handleMessage_sessionWelcome_fallsBackToDefaultWhenTimeoutAbsent() {
        String message = """
            {
              "metadata": { "message_type": "session_welcome" },
              "payload": { "session": { "id": "session-abc" } }
            }
            """;

        service.handleMessage(user, token, message, false);

        Map<UUID, Long> timeouts = (Map<UUID, Long>) ReflectionTestUtils.getField(service, "keepaliveTimeoutSeconds");
        assertThat(timeouts).containsEntry(user.getId(), 10L);
    }

    @Test
    void handleMessage_sessionWelcome_onReconnectedSession_doesNotResubscribe() {
        String message = """
            {
              "metadata": { "message_type": "session_welcome" },
              "payload": { "session": { "id": "session-abc", "keepalive_timeout_seconds": 30 } }
            }
            """;

        service.handleMessage(user, token, message, true);

        // Subscriptions carry over when reconnecting via reconnect_url — no POST expected
        verify(restClient, never()).post();
        // But the keepalive timeout of the new session must still be recorded
        Map<UUID, Long> timeouts = (Map<UUID, Long>) ReflectionTestUtils.getField(service, "keepaliveTimeoutSeconds");
        assertThat(timeouts).containsEntry(user.getId(), 30L);
    }

    @Test
    void onClose_whenListenerWebSocketIsCurrent_deregistersConnection() {
        WebSocket ws = mock(WebSocket.class);
        TwitchEventSubService.EventSubListener listener =
            service.new EventSubListener(user, token, 1L, false);

        Map<UUID, WebSocket> connections = (Map<UUID, WebSocket>) ReflectionTestUtils.getField(service, "connections");
        connections.put(user.getId(), ws);

        listener.onClose(ws, WebSocket.NORMAL_CLOSURE, "server initiated");

        assertThat(connections).doesNotContainKey(user.getId());
    }

    @Test
    void onClose_whenListenerWebSocketHasBeenReplaced_leavesSuccessorRegistered() {
        WebSocket staleWs = mock(WebSocket.class);
        WebSocket freshWs = mock(WebSocket.class);
        TwitchEventSubService.EventSubListener staleListener =
            service.new EventSubListener(user, token, 1L, false);

        // Simulate session_reconnect: connections now points to freshWs, staleListener is orphaned.
        Map<UUID, WebSocket> connections = (Map<UUID, WebSocket>) ReflectionTestUtils.getField(service, "connections");
        connections.put(user.getId(), freshWs);

        staleListener.onClose(staleWs, WebSocket.NORMAL_CLOSURE, "replaced");

        assertThat(connections).containsEntry(user.getId(), freshWs);
    }

    @Test
    void onWatchdogTrigger_whenListenerWebSocketIsCurrent_abortsAndRemovesFromConnections() {
        WebSocket ws = mock(WebSocket.class);
        TwitchEventSubService.EventSubListener listener =
            service.new EventSubListener(user, token, 1L, false);
        ReflectionTestUtils.setField(listener, "webSocket", ws);

        Map<UUID, WebSocket> connections = (Map<UUID, WebSocket>) ReflectionTestUtils.getField(service, "connections");
        connections.put(user.getId(), ws);

        service.onWatchdogTrigger(user, listener);

        verify(ws).abort();
        assertThat(connections).doesNotContainKey(user.getId());
    }

    @Test
    void onWatchdogTrigger_whenListenerWebSocketHasBeenReplaced_isIdempotentNoOp() {
        WebSocket staleWs = mock(WebSocket.class);
        WebSocket freshWs = mock(WebSocket.class);
        TwitchEventSubService.EventSubListener staleListener =
            service.new EventSubListener(user, token, 1L, false);
        ReflectionTestUtils.setField(staleListener, "webSocket", staleWs);

        // Simulate session_reconnect: connections now points to freshWs, staleListener is orphaned.
        Map<UUID, WebSocket> connections = (Map<UUID, WebSocket>) ReflectionTestUtils.getField(service, "connections");
        connections.put(user.getId(), freshWs);

        service.onWatchdogTrigger(user, staleListener);

        verify(staleWs, never()).abort();
        verify(freshWs, never()).abort();
        assertThat(connections).containsEntry(user.getId(), freshWs);
    }
}
