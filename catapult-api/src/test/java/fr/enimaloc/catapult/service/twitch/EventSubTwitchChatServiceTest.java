package fr.enimaloc.catapult.service.twitch;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventSubTwitchChatServiceTest {

    @Mock private OAuthTokenRepository oAuthTokenRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private TwitchTokenService twitchTokenService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RestClient restClient;
    @Mock private TwitchHelixChannelClient helix;
    @Mock private SystemTwitchAccountService systemTwitchAccountService;
    @Mock private ExternalApiObservations apiObservations;
    @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Mock private RestClient.RequestBodyUriSpec postUriSpec;
    @Mock private RestClient.RequestBodySpec postBodySpec;
    @Mock private RestClient.ResponseSpec postResponseSpec;

    @InjectMocks private EventSubTwitchChatService service;

    private UserAccount user;
    private OAuthToken token;

    private static final String WELCOME_MESSAGE = """
        {
          "metadata": { "message_type": "session_welcome" },
          "payload": { "session": { "id": "session-abc", "keepalive_timeout_seconds": 30 } }
        }
        """;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("broadcaster-123");
        user.setTwitchUsername("streamer");

        token = new OAuthToken();
        token.setAccessToken("encrypted");

        when(twitchTokenService.resolveAccessToken(eq(token), eq(user))).thenReturn("fresh-token");
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.of(token));

        when(restClient.post()).thenReturn(postUriSpec);
        when(postUriSpec.uri(anyString())).thenReturn(postBodySpec);
        when(postBodySpec.header(anyString(), anyString())).thenReturn(postBodySpec);
        when(postBodySpec.body(any(Object.class))).thenReturn(postBodySpec);
        when(postBodySpec.retrieve()).thenReturn(postResponseSpec);
        when(postResponseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());

        doAnswer(inv -> {
            inv.getArgument(2, Runnable.class).run();
            return null;
        }).when(apiObservations).observeRun(anyString(), anyString(), any(Runnable.class));

        ReflectionTestUtils.setField(service, "twitchClientId", "test-client-id");
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
    }

    @Test
    void extractRole_broadcasterBadgeWinsOverEverything() throws Exception {
        var event = new ObjectMapper().readTree(
            "{\"badges\":[{\"set_id\":\"subscriber\",\"info\":\"3\"},{\"set_id\":\"broadcaster\",\"info\":\"1\"}]}");
        var role = ReflectionTestUtils.invokeMethod(service, "extractRole", event);
        assertThat(role).isEqualTo(fr.enimaloc.catapult.chat.ChatCommandEvent.SenderRole.BROADCASTER);
    }

    @Test
    void extractRole_subsBadgeMapsToSubsTier() throws Exception {
        var event = new ObjectMapper().readTree("{\"badges\":[{\"set_id\":\"subscriber\",\"info\":\"3\"}]}");
        var role = ReflectionTestUtils.invokeMethod(service, "extractRole", event);
        assertThat(role).isEqualTo(fr.enimaloc.catapult.chat.ChatCommandEvent.SenderRole.SUBS);
    }

    @Test
    void extractRole_vipBadgeMapsToVipTier() throws Exception {
        var event = new ObjectMapper().readTree("{\"badges\":[{\"set_id\":\"vip\",\"info\":\"1\"}]}");
        var role = ReflectionTestUtils.invokeMethod(service, "extractRole", event);
        assertThat(role).isEqualTo(fr.enimaloc.catapult.chat.ChatCommandEvent.SenderRole.VIP);
    }

    @Test
    void extractRole_noRecognizedBadgeFallsBackToViewers() throws Exception {
        var event = new ObjectMapper().readTree("{\"badges\":[]}");
        var role = ReflectionTestUtils.invokeMethod(service, "extractRole", event);
        assertThat(role).isEqualTo(fr.enimaloc.catapult.chat.ChatCommandEvent.SenderRole.VIEWERS);
    }

    @Test
    void handleMessage_sessionWelcome_resolvesTokenThroughTokenService() {
        service.handleMessage(user, token, WELCOME_MESSAGE, false);

        // Regression: subscribing with the raw stored token 401s once it expires.
        // The token must go through resolveAccessToken (refreshes when expired).
        verify(twitchTokenService).resolveAccessToken(token, user);
        // channel.chat.message + channel_points redemption
        verify(restClient, times(2)).post();
    }

    @Test
    void handleMessage_sessionWelcome_onReconnectedSession_doesNotResubscribe() {
        service.handleMessage(user, token, WELCOME_MESSAGE, true);

        // Subscriptions carry over when reconnecting via reconnect_url — no POST expected
        verify(restClient, never()).post();
        Map<UUID, Long> timeouts = (Map<UUID, Long>) connectionState("keepaliveTimeoutSeconds");
        assertThat(timeouts).containsEntry(user.getId(), 30L);
    }

    @Test
    void handleMessage_sessionWelcome_on401_refreshesTokenAndRetries() {
        when(postResponseSpec.toBodilessEntity())
            .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                new HttpHeaders(), new byte[0], StandardCharsets.UTF_8))
            .thenReturn(ResponseEntity.ok().build());
        when(twitchTokenService.refreshAccessToken(token, user)).thenReturn("refreshed-token");

        service.handleMessage(user, token, WELCOME_MESSAGE, false);

        verify(twitchTokenService).refreshAccessToken(token, user);
        // 2 subscriptions + 1 retry after refresh
        verify(restClient, times(3)).post();
    }

    @Test
    void handleMessage_sessionWelcome_resetsRetryBackoffAfterPriorFailures() {
        // Simulate a prior string of failures that inflated the backoff far past the base delay.
        Map<UUID, Long> retryDelays = (Map<UUID, Long>) connectionState("retryDelaySeconds");
        retryDelays.put(user.getId(), 32L);

        service.handleMessage(user, token, WELCOME_MESSAGE, false);

        // A successful session must reset the backoff so the next disconnect reconnects promptly.
        assertThat(retryDelays).doesNotContainKey(user.getId());
    }

    @Test
    void helixCalls_runWithTheResolvedStreamerToken() {
        java.time.Instant at = java.time.Instant.parse("2024-01-01T00:00:00Z");
        when(helix.followedAt(user, "fresh-token", "fan")).thenReturn(Optional.of(at));
        when(helix.followedAtById(user, "fresh-token", "42")).thenReturn(Optional.of(at));
        TwitchStreamInfo live = new TwitchStreamInfo("t", "g", 1, at);
        when(helix.streamInfo(user, "fresh-token")).thenReturn(Optional.of(live));
        TwitchUserProfile profile = new TwitchUserProfile("Fan", at);
        when(helix.userProfile("fresh-token", "fan")).thenReturn(Optional.of(profile));

        service.ban(user, "troll", "spam");
        service.timeout(user, "troll", 30, "calm down");
        service.timeout(user, "troll", -1, null);
        service.unban(user, "troll");
        service.shoutout(user, "friend");

        verify(helix).moderate(user, "fresh-token", "troll", 0, "spam");
        verify(helix).moderate(user, "fresh-token", "troll", 30, "calm down");
        verify(helix).unban(user, "fresh-token", "troll");
        verify(helix).shoutout(user, "fresh-token", "friend");
        assertThat(service.getFollowedAt(user, "fan")).contains(at);
        assertThat(service.getFollowedAtById(user, "42")).contains(at);
        assertThat(service.getStreamInfo(user)).contains(live);
        assertThat(service.getUserProfile(user, "fan")).contains(profile);
    }

    private Object connectionState(String field) {
        return ReflectionTestUtils.getField(ReflectionTestUtils.getField(service, "connections"), field);
    }
}
