package fr.enimaloc.catapult.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.AccountCreatedEvent;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** EventSubTwitchChatService's connection lifecycle, notifications and Helix chat sends. */
class EventSubTwitchChatServiceFlowsTest {

    private static final String CHAT_URL = "https://api.twitch.tv/helix/chat/messages";
    private static final String SUBSCRIPTIONS_URL = "https://api.twitch.tv/helix/eventsub/subscriptions";

    private final OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final TwitchTokenService tokenService = mock(TwitchTokenService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final SystemTwitchAccountService bot = mock(SystemTwitchAccountService.class);
    private final TwitchChatRateLimiter rateLimiter = mock(TwitchChatRateLimiter.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final HttpClient httpClient = mock(HttpClient.class);
    private final WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
    private final List<WebSocket.Listener> listeners = new CopyOnWriteArrayList<>();
    private MockRestServiceServer helix;
    private EventSubTwitchChatService service;
    private UserAccount user;
    private OAuthToken token;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        helix = MockRestServiceServer.bindTo(builder).build();
        service = new EventSubTwitchChatService(tokens, users, tokenService, publisher,
                mock(TwitchHelixChannelClient.class), builder.build(), new ObjectMapper(), bot, meters,
                new ExternalApiObservations(ObservationRegistry.NOOP, meters), rateLimiter);
        ReflectionTestUtils.setField(service, "twitchClientId", "client-id");
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(service, "connections"), "httpClient", httpClient);

        user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("b-1");
        user.setTwitchUsername("streamer");
        token = new OAuthToken();
        when(tokens.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.of(token));
        when(tokenService.resolveAccessToken(token, user)).thenReturn("streamer-token");
        when(rateLimiter.acquire(anyString())).thenReturn(true);
        when(httpClient.newWebSocketBuilder()).thenReturn(wsBuilder);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    /** Each WebSocket handshake hands back a fresh mock socket, opening it on the captured listener. */
    private void handshakesSucceed() {
        when(wsBuilder.buildAsync(any(URI.class), any(WebSocket.Listener.class))).thenAnswer(call -> {
            WebSocket.Listener listener = call.getArgument(1);
            listeners.add(listener);
            WebSocket ws = mock(WebSocket.class);
            listener.onOpen(ws);
            return CompletableFuture.completedFuture(ws);
        });
    }

    private WebSocket socketOf(WebSocket.Listener listener) {
        return (WebSocket) ReflectionTestUtils.getField(listener, "webSocket");
    }

    private double counter(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private static String chatMessage(String text, String... badges) {
        StringBuilder badgeJson = new StringBuilder();
        for (String badge : badges) {
            if (!badgeJson.isEmpty()) badgeJson.append(',');
            badgeJson.append("{\"set_id\": \"").append(badge).append("\"}");
        }
        return """
                {"metadata": {"message_type": "notification", "subscription_type": "channel.chat.message"},
                 "payload": {"event": {"chatter_user_id": "c-9", "message": {"text": "%s"}, "badges": [%s]}}}
                """.formatted(text, badgeJson);
    }

    @Nested
    class Lifecycle {
        @Test
        void init_connectsEveryActiveBotUser() {
            handshakesSucceed();
            when(users.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)).thenReturn(List.of(user));

            service.init();

            verify(wsBuilder).buildAsync(eq(URI.create("wss://eventsub.wss.twitch.tv/ws")), any());
            assertThat(service.connectionCount()).isEqualTo(1);
            assertThat(meters.find("catapult.chat.messages").tag("transport", "eventsub").counter()).isNotNull();
        }

        @Test
        void accountCreation_connects_andWithoutTokenNothingHappens() {
            handshakesSucceed();
            service.onAccountCreated(new AccountCreatedEvent(this, user));
            assertThat(service.connectionCount()).isEqualTo(1);

            UserAccount tokenless = new UserAccount();
            tokenless.setId(UUID.randomUUID());
            when(tokens.findByUserAndProvider(tokenless, OAuthToken.Provider.TWITCH)).thenReturn(Optional.empty());
            service.connect(tokenless);
            verify(wsBuilder, times(1)).buildAsync(any(), any());
        }

        @Test
        void disconnect_closesTheSocket_andAServerCloseThenNeverReconnects() {
            handshakesSucceed();
            service.connect(user);
            WebSocket.Listener listener = listeners.getFirst();
            WebSocket ws = socketOf(listener);

            service.disconnect(user);
            listener.onClose(ws, 1000, "bye");

            verify(ws).sendClose(WebSocket.NORMAL_CLOSURE, "bot disabled");
            assertThat(service.connectionCount()).isZero();
        }

        @Test
        void shutdown_closesEverySocket() {
            handshakesSucceed();
            service.connect(user);
            WebSocket ws = socketOf(listeners.getFirst());

            service.shutdown();

            verify(ws).sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            assertThat(service.connectionCount()).isZero();
        }

        @Test
        void serverClose_afterConnect_reconnectsOnTheCanonicalUrl() {
            handshakesSucceed();
            service.connect(user);
            WebSocket.Listener listener = listeners.getFirst();

            listener.onClose(socketOf(listener), 4000, "server going away");

            verify(wsBuilder, timeout(3000).times(2)).buildAsync(eq(URI.create("wss://eventsub.wss.twitch.tv/ws")), any());
        }

    }

    @Nested
    class Messages {
        @Test
        void chatCommands_arePublishedWithArgsRoleAndSender() {
            service.handleMessage(user, token, chatMessage("!so friend now", "subscriber", "mod"), false);

            ArgumentCaptor<ChatCommandEvent> event = ArgumentCaptor.forClass(ChatCommandEvent.class);
            verify(publisher).publishEvent(event.capture());
            assertThat(event.getValue().getCommand()).isEqualTo("!so");
            assertThat(event.getValue().getArgs()).containsExactly("friend", "now");
            assertThat(event.getValue().getSenderRole()).isEqualTo(ChatCommandEvent.SenderRole.MODERATOR);
            assertThat(event.getValue().getSenderTwitchId()).isEqualTo("c-9");
            assertThat(counter("catapult.chat.messages", "transport", "eventsub")).isEqualTo(1);
        }

        @Test
        void plainChat_isOnlyCounted() {
            service.handleMessage(user, token, chatMessage("hello"), false);

            verify(publisher, never()).publishEvent(any());
            assertThat(counter("catapult.chat.messages", "transport", "eventsub")).isEqualTo(1);
        }

        @Test
        void rewardRedemptions_becomeRewardCommands() {
            service.handleMessage(user, token, """
                    {"metadata": {"message_type": "notification",
                                  "subscription_type": "channel.channel_points_custom_reward_redemption.add"},
                     "payload": {"event": {"reward": {"title": "Hydrate"}}}}""", false);

            ArgumentCaptor<ChatCommandEvent> event = ArgumentCaptor.forClass(ChatCommandEvent.class);
            verify(publisher).publishEvent(event.capture());
            assertThat(event.getValue().getCommand()).isEqualTo("reward:Hydrate");
            assertThat(event.getValue().getSenderRole()).isEqualTo(ChatCommandEvent.SenderRole.VIEWERS);
        }

        @Test
        void otherNotificationsAndMessageTypes_areIgnored() {
            for (String type : List.of("session_keepalive", "revocation", "something_new")) {
                service.handleMessage(user, token, "{\"metadata\": {\"message_type\": \"" + type + "\"}}", false);
            }
            service.handleMessage(user, token,
                    "{\"metadata\": {\"message_type\": \"notification\", \"subscription_type\": \"channel.follow\"}}", false);
            service.handleMessage(user, token, "not json", false);

            verify(publisher, never()).publishEvent(any());
        }

        @Test
        void sessionReconnect_opensTheGivenUrlWithAFreshToken() {
            handshakesSucceed();
            service.handleMessage(user, token, """
                    {"metadata": {"message_type": "session_reconnect"},
                     "payload": {"session": {"reconnect_url": "wss://eventsub.example/reconnect?id=1"}}}""", false);

            verify(wsBuilder).buildAsync(eq(URI.create("wss://eventsub.example/reconnect?id=1")), any());
        }

        @Test
        void welcome_subscribesToChatAndRewards() {
            helix.expect(requestTo(SUBSCRIPTIONS_URL)).andExpect(method(HttpMethod.POST))
                    .andExpect(header("Authorization", "Bearer streamer-token"))
                    .andExpect(content().json("{\"type\": \"channel.chat.message\", \"version\": \"1\","
                            + " \"condition\": {\"broadcaster_user_id\": \"b-1\", \"user_id\": \"b-1\"},"
                            + " \"transport\": {\"method\": \"websocket\", \"session_id\": \"s-1\"}}"))
                    .andRespond(withSuccess());
            helix.expect(requestTo(SUBSCRIPTIONS_URL))
                    .andExpect(content().json("{\"type\": \"channel.channel_points_custom_reward_redemption.add\"}"))
                    .andRespond(withServerError());

            service.handleMessage(user, token,
                    "{\"metadata\": {\"message_type\": \"session_welcome\"}, \"payload\": {\"session\": {\"id\": \"s-1\"}}}", false);

            helix.verify();
        }

        @Test
        void welcome_on401_withoutARefreshedToken_givesUp() {
            helix.expect(requestTo(SUBSCRIPTIONS_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
            helix.expect(requestTo(SUBSCRIPTIONS_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
            when(tokenService.refreshAccessToken(token, user)).thenReturn(null);

            service.handleMessage(user, token,
                    "{\"metadata\": {\"message_type\": \"session_welcome\"}, \"payload\": {\"session\": {\"id\": \"s-1\"}}}", false);

            helix.verify();
        }

        @Test
        void welcome_on401_retryFailuresAreSwallowed() {
            helix.expect(requestTo(SUBSCRIPTIONS_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
            helix.expect(requestTo(SUBSCRIPTIONS_URL)).andExpect(header("Authorization", "Bearer refreshed"))
                    .andRespond(withServerError());
            helix.expect(requestTo(SUBSCRIPTIONS_URL)).andRespond(withSuccess());
            when(tokenService.refreshAccessToken(token, user)).thenReturn("refreshed");

            service.handleMessage(user, token,
                    "{\"metadata\": {\"message_type\": \"session_welcome\"}, \"payload\": {\"session\": {\"id\": \"s-1\"}}}", false);

            helix.verify();
        }
    }

    @Nested
    class Sending {
        private void chatSend(String bearer, String senderId) {
            helix.expect(requestTo(CHAT_URL)).andExpect(method(HttpMethod.POST))
                    .andExpect(header("Authorization", "Bearer " + bearer))
                    .andExpect(content().json("{\"broadcaster_id\": \"b-1\", \"sender_id\": \"" + senderId + "\"}"))
                    .andRespond(withSuccess());
        }

        @Test
        void throughTheSystemBot_whenConfigured() {
            when(bot.getAccessToken()).thenReturn("bot-token");
            when(bot.getSystemTwitchId()).thenReturn("bot-1");
            String longMessage = "x".repeat(700);
            chatSend("bot-token", "bot-1");
            chatSend("bot-token", "bot-1");

            service.sendMessage(user, longMessage);

            helix.verify();
            assertThat(counter("catapult.chat.commands.send", "sender", "bot", "outcome", "success")).isEqualTo(2);
        }

        @Test
        void fallsBackToTheStreamer_whenTheBotFails() {
            when(bot.getAccessToken()).thenReturn("bot-token");
            when(bot.getSystemTwitchId()).thenReturn("bot-1");
            helix.expect(requestTo(CHAT_URL)).andRespond(withServerError());
            chatSend("streamer-token", "b-1");

            service.sendMessage(user, "hello");

            helix.verify();
            assertThat(counter("catapult.chat.commands.send", "sender", "bot", "outcome", "failed")).isEqualTo(1);
            assertThat(counter("catapult.chat.commands.send", "sender", "streamer", "outcome", "success")).isEqualTo(1);
        }

        @Test
        void withoutBotOrBotId_usesTheStreamer() {
            when(bot.getAccessToken()).thenReturn("bot-token");
            chatSend("streamer-token", "b-1");

            service.sendMessage(user, "hello");

            helix.verify();
        }

        @Test
        void withoutBotNorStreamerToken_nothingIsSent() {
            when(tokens.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.empty());

            service.sendMessage(user, "hello");
            service.sendMessage(user, "");

            helix.verify();
        }

        @Test
        void rateLimitedSenders_areSkipped() {
            when(rateLimiter.acquire("b-1")).thenReturn(false);

            service.sendMessage(user, "hello");

            assertThat(counter("catapult.chat.commands.send", "sender", "streamer", "outcome", "rate_limited")).isEqualTo(1);
        }

        @Test
        void tooManyRequests_pauseTheSender_untilTheResetDeadline() {
            HttpHeaders reset = new HttpHeaders();
            reset.add("Ratelimit-Reset", String.valueOf(Instant.now().getEpochSecond() + 20));
            HttpHeaders badReset = new HttpHeaders();
            badReset.add("Ratelimit-Reset", "soon");
            badReset.add("Retry-After", "7");
            HttpHeaders badRetry = new HttpHeaders();
            badRetry.add("Retry-After", "later");
            helix.expect(requestTo(CHAT_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(reset));
            helix.expect(requestTo(CHAT_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(badReset));
            helix.expect(requestTo(CHAT_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(badRetry));
            helix.expect(requestTo(CHAT_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

            for (int i = 0; i < 4; i++) service.sendMessage(user, "hello");

            ArgumentCaptor<Long> pauses = ArgumentCaptor.forClass(Long.class);
            verify(rateLimiter, times(4)).onRateLimitResponse(eq("b-1"), pauses.capture());
            assertThat(pauses.getAllValues().getFirst()).isBetween(18L, 20L);
            assertThat(pauses.getAllValues().subList(1, 4)).containsExactly(7L, 30L, 30L);
        }
    }
}
