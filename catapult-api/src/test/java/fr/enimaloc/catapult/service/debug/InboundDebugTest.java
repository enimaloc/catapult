package fr.enimaloc.catapult.service.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.api.connect.ApiSteamConnectController;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.debug.DebugExchange;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.debug.DebugExchangeRepository;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry.DebugMode;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import fr.enimaloc.catapult.service.notification.CatapultCategoryChangeStateService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.notification.TwitchatEventDispatcher;
import fr.enimaloc.catapult.service.twitch.EventSubTwitchChatService;
import fr.enimaloc.catapult.service.twitch.SystemTwitchAccountService;
import fr.enimaloc.catapult.service.twitch.TwitchChatRateLimiter;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import fr.enimaloc.catapult.service.twitch.TwitchHelixChannelClient;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** What catapult receives for a member in debug mode is recorded inbound, and never disturbs its processing. */
class InboundDebugTest {

    private static final String STREAM_ONLINE = """
            {"metadata":{"message_type":"notification","subscription_type":"stream.online"},
             "payload":{"event":{"broadcaster_user_id":"b-1"}}}""";
    private static final String KEEPALIVE = """
            {"metadata":{"message_type":"session_keepalive"},"payload":{}}""";
    private static final String CHAT_MESSAGE = """
            {"metadata":{"message_type":"notification","subscription_type":"channel.chat.message"},
             "payload":{"event":{"broadcaster_user_id":"b-1","chatter_user_login":"viewer",
               "message":{"text":"hello"}}}}""";

    private final DebugMemberRegistry registry = mock(DebugMemberRegistry.class);
    private final DebugExchangeRecorder recorder = mock(DebugExchangeRecorder.class);
    private final StreamStateService streamState = mock(StreamStateService.class);
    private UserAccount user;
    private OAuthToken token;
    private TwitchEventSubService eventSub;
    private EventSubTwitchChatService chat;

    @BeforeEach
    void setUp() {
        when(registry.modeOf(any())).thenReturn(Optional.empty());
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("b-1");
        user.setTwitchUsername("streamer");
        token = new OAuthToken();
    }

    @AfterEach
    void tearDown() {
        if (eventSub != null) {
            eventSub.shutdown();
        }
        if (chat != null) {
            chat.shutdown();
        }
    }

    private void debugged(UUID id, boolean reveal) {
        when(registry.modeOf(id)).thenReturn(Optional.of(new DebugMode(id, reveal)));
        when(registry.hasAny()).thenReturn(true);
    }

    private TwitchEventSubService eventSub() {
        eventSub = new TwitchEventSubService(mock(OAuthTokenRepository.class), mock(UserAccountRepository.class),
                mock(TwitchTokenService.class), streamState, mock(ApplicationEventPublisher.class),
                RestClient.create(), new ObjectMapper(), mock(TwitchatEventDispatcher.class),
                mock(CatapultCategoryChangeStateService.class));
        eventSub.setDebugRecording(new DebugRecording(registry, recorder));
        return eventSub;
    }

    private EventSubTwitchChatService chat() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        chat = new EventSubTwitchChatService(mock(OAuthTokenRepository.class), mock(UserAccountRepository.class),
                mock(TwitchTokenService.class), mock(ApplicationEventPublisher.class),
                mock(TwitchHelixChannelClient.class), RestClient.create(), new ObjectMapper(),
                mock(SystemTwitchAccountService.class), meters,
                new ExternalApiObservations(ObservationRegistry.NOOP, meters), mock(TwitchChatRateLimiter.class));
        chat.setDebugRecording(new DebugRecording(registry, recorder));
        return chat;
    }

    private static void deliver(Object service, UserAccount user, OAuthToken token, String message) {
        ReflectionTestUtils.invokeMethod(service, "handleMessage", user, token, message, false);
    }

    private RawExchange recordedInbound(String operation) {
        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(user.getId()), eq(Direction.IN), raw.capture(), eq(operation), eq(false),
                eq("twitch"));
        return raw.getValue();
    }

    @Test
    void eventSubMessage_isRecordedInbound() {
        debugged(user.getId(), false);

        deliver(eventSub(), user, token, STREAM_ONLINE);

        RawExchange raw = recordedInbound("eventsub.stream.online");
        assertThat(raw.method()).isEqualTo("WS");
        assertThat(raw.uri().toString()).isEqualTo("wss://eventsub.wss.twitch.tv/ws");
        assertThat(new String(raw.requestBody(), StandardCharsets.UTF_8)).isEqualTo(STREAM_ONLINE);
        assertThat(raw.status()).isNull();
        assertThat(raw.responseBody()).isNull();
        verify(streamState).setLive(user, true);
    }

    @Test
    void eventSubMessageWithoutSubscription_isNamedAfterItsMessageType() {
        debugged(user.getId(), false);

        deliver(eventSub(), user, token, KEEPALIVE);

        recordedInbound("eventsub.session_keepalive");
    }

    @Test
    void eventSubMessage_memberNotInDebug_nothingRecorded() {
        deliver(eventSub(), user, token, STREAM_ONLINE);

        verify(recorder, never()).record(any(), any(), any(), any(), anyBoolean(), any());
        verify(recorder, never()).record(any(), any(), any(), any(), anyBoolean());
        verify(streamState).setLive(user, true);
    }

    @Test
    void eventSubMessage_recordingFails_processingGoesOn() {
        debugged(user.getId(), false);
        doThrow(new IllegalStateException("boom")).when(recorder)
                .record(any(), any(), any(), any(), anyBoolean(), any());

        assertThatCode(() -> deliver(eventSub(), user, token, STREAM_ONLINE)).doesNotThrowAnyException();

        verify(streamState).setLive(user, true);
    }

    @Test
    void eventSubMessage_unparseable_isStillRecorded() {
        debugged(user.getId(), false);

        deliver(eventSub(), user, token, "not json");

        RawExchange raw = recordedInbound(null);
        assertThat(new String(raw.requestBody(), StandardCharsets.UTF_8)).isEqualTo("not json");
    }

    @Test
    void chatMessage_isRecordedInbound() {
        debugged(user.getId(), false);

        deliver(chat(), user, token, CHAT_MESSAGE);

        RawExchange raw = recordedInbound("eventsub.channel.chat.message");
        assertThat(raw.method()).isEqualTo("WS");
        assertThat(new String(raw.requestBody(), StandardCharsets.UTF_8)).isEqualTo(CHAT_MESSAGE);
    }

    // --- Steam OpenID callback -------------------------------------------------------------------------

    private final DebugExchangeRepository exchanges = mock(DebugExchangeRepository.class);

    /** A real recorder (synchronous), so that what is stored, redaction included, can be read back. */
    private DebugExchangeRecorder realRecorder() {
        OtherMemberMasker masker = mock(OtherMemberMasker.class);
        when(masker.mask(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(exchanges.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return new DebugExchangeRecorder(registry, new DebugRedactor(), masker, exchanges,
                new ExternalServices("https://git.enimaloc.fr"), List.of(), () -> null, Runnable::run);
    }

    private List<DebugExchange> steamCallback(boolean reveal) {
        debugged(user.getId(), reveal);
        UserAccountRepository accounts = mock(UserAccountRepository.class);
        when(accounts.findById(user.getId())).thenReturn(Optional.of(user));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer steam = MockRestServiceServer.bindTo(builder).build();
        steam.expect(requestTo("https://steamcommunity.com/openid/login"))
                .andRespond(withSuccess("is_valid:true\n", MediaType.TEXT_PLAIN));
        ApiSteamConnectController controller = new ApiSteamConnectController(accounts,
                mock(ApplicationEventPublisher.class), mock(ChannelEventPublisher.class), builder.build());
        ReflectionTestUtils.setField(controller, "apiBaseUrl", "http://api.test");
        ReflectionTestUtils.setField(controller, "webBaseUrl", "http://web.test");
        controller.setDebugRecording(new DebugRecording(registry, realRecorder()));
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(user.getId().toString()).build();
        String returnTo = UriComponentsBuilder.fromUriString(controller.start(jwt).redirectUrl()).build()
                .getQueryParams().getFirst("openid.return_to");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("nonce", returnTo.substring(returnTo.indexOf("nonce=") + "nonce=".length()));
        params.put("openid.mode", "id_res");
        params.put("openid.claimed_id", "https://steamcommunity.com/openid/id/76561198000000000");
        params.put("openid.sig", "SIGNATURE+abc=");

        controller.callback(params);

        ArgumentCaptor<DebugExchange> saved = ArgumentCaptor.forClass(DebugExchange.class);
        verify(exchanges, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        return saved.getAllValues().stream().filter(e -> e.getDirection() == Direction.IN).toList();
    }

    @Test
    void steamCallback_isRecordedInbound_withSecretsPerChoice() {
        List<DebugExchange> inbound = steamCallback(false);

        assertThat(inbound).hasSize(1);
        DebugExchange e = inbound.getFirst();
        assertThat(e.getService()).isEqualTo("steam");
        assertThat(e.getMethod()).isEqualTo("GET");
        assertThat(e.isFiltered()).isFalse();
        assertThat(e.getUrl()).startsWith("http://api.test/api/connect/steam/callback?nonce=")
                .contains("openid.mode=id_res")
                .contains("openid.claimed_id=https")
                .contains("openid.sig=[masqué]")
                .doesNotContain("SIGNATURE");
        assertThat(e.getStatus()).isNull();
        assertThat(e.getResponseBody()).isNull();
    }

    @Test
    void steamCallback_revealedSecrets_keepTheSignature() {
        List<DebugExchange> inbound = steamCallback(true);

        assertThat(inbound).hasSize(1);
        assertThat(inbound.getFirst().getUrl()).contains("openid.sig=SIGNATURE");
    }
}
