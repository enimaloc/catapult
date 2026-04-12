package fr.esportline.catapult.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.esportline.catapult.domain.OAuthToken;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.event.StreamOfflineEvent;
import fr.esportline.catapult.event.StreamOnlineEvent;
import fr.esportline.catapult.repository.OAuthTokenRepository;
import fr.esportline.catapult.repository.UserAccountRepository;
import fr.esportline.catapult.security.TokenEncryptionService;
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

import java.util.List;
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
    @Mock private TokenEncryptionService tokenEncryptionService;
    @Mock private StreamStateService streamStateService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RestClient restClient;

    @Mock private RestClient.RequestBodyUriSpec postUriSpec;
    @Mock private RestClient.RequestBodySpec postBodySpec;
    @Mock private RestClient.ResponseSpec postResponseSpec;

    @InjectMocks private TwitchEventSubService service;

    private UserAccount user;
    private OAuthToken token;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("broadcaster-123");

        token = new OAuthToken();
        token.setAccessToken("encrypted");

        when(tokenEncryptionService.decrypt("encrypted")).thenReturn("decrypted-token");
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

        service.handleMessage(user, token, message);

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

        service.handleMessage(user, token, message);

        verify(streamStateService).setLive(user, false);
        ArgumentCaptor<StreamOfflineEvent> captor = ArgumentCaptor.forClass(StreamOfflineEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(user);
    }

    @Test
    void handleMessage_sessionWelcome_subscribesToStreamOnlineAndOffline() {
        String message = """
            {
              "metadata": { "message_type": "session_welcome" },
              "payload": { "session": { "id": "session-abc" } }
            }
            """;

        service.handleMessage(user, token, message);

        // One POST for stream.online, one for stream.offline
        verify(restClient, times(2)).post();
    }

    @Test
    void handleMessage_invalidJson_doesNotThrow() {
        assertThatNoException().isThrownBy(
            () -> service.handleMessage(user, token, "not-json")
        );
    }

    @Test
    void handleMessage_unknownMessageType_doesNotThrow() {
        String message = """
            { "metadata": { "message_type": "session_keepalive" }, "payload": {} }
            """;

        assertThatNoException().isThrownBy(
            () -> service.handleMessage(user, token, message)
        );
    }
}
