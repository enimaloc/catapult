package fr.enimaloc.catapult.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.event.AccountCreatedEvent;
import fr.enimaloc.catapult.event.ChannelCategoryChangedEvent;
import fr.enimaloc.catapult.event.ChannelCclChangedEvent;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.notification.CatapultCategoryChangeStateService;
import fr.enimaloc.catapult.service.notification.TwitchatNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

/** TwitchEventSubService's connection lifecycle, subscriptions and initial channel/stream state. */
class TwitchEventSubServiceFlowsTest {

    private static final String SUBSCRIPTIONS = "https://api.twitch.tv/helix/eventsub/subscriptions";
    private static final String CHANNELS = "https://api.twitch.tv/helix/channels?broadcaster_id=b-1";
    private static final String STREAMS = "https://api.twitch.tv/helix/streams?user_id=b-1";
    private static final String WELCOME =
            "{\"metadata\": {\"message_type\": \"session_welcome\"}, \"payload\": {\"session\": {\"id\": \"s-1\"}}}";

    private final OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final TwitchTokenService tokenService = mock(TwitchTokenService.class);
    private final StreamStateService streamState = mock(StreamStateService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final TwitchatNotifier twitchat = mock(TwitchatNotifier.class);
    private final CatapultCategoryChangeStateService selfChanges = mock(CatapultCategoryChangeStateService.class);
    private final HttpClient httpClient = mock(HttpClient.class);
    private final WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
    private final List<WebSocket.Listener> listeners = new CopyOnWriteArrayList<>();
    private MockRestServiceServer helix;
    private TwitchEventSubService service;
    private UserAccount user;
    private OAuthToken token;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        helix = MockRestServiceServer.bindTo(builder).build();
        service = new TwitchEventSubService(tokens, users, tokenService, streamState, publisher, builder.build(),
                new ObjectMapper(), twitchat, selfChanges);
        ReflectionTestUtils.setField(service, "twitchClientId", "client-id");
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(service, "connections"), "httpClient", httpClient);

        user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("b-1");
        token = new OAuthToken();
        when(tokens.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.of(token));
        when(tokenService.resolveAccessToken(token, user)).thenReturn("streamer-token");
        when(httpClient.newWebSocketBuilder()).thenReturn(wsBuilder);
        when(wsBuilder.buildAsync(any(URI.class), any(WebSocket.Listener.class))).thenAnswer(call -> {
            WebSocket.Listener listener = call.getArgument(1);
            listeners.add(listener);
            WebSocket ws = mock(WebSocket.class);
            listener.onOpen(ws);
            return CompletableFuture.completedFuture(ws);
        });
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private WebSocket socketOf(WebSocket.Listener listener) {
        return (WebSocket) ReflectionTestUtils.getField(listener, "webSocket");
    }

    private void json(String url, String body) {
        helix.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer streamer-token"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void subscriptionsSucceed() {
        for (int i = 0; i < 3; i++) helix.expect(requestTo(SUBSCRIPTIONS)).andRespond(withSuccess());
    }

    private static String channelUpdate(String categoryId, String categoryName, String... ccls) {
        return """
                {"metadata": {"message_type": "notification", "subscription_type": "channel.update"},
                 "payload": {"event": {"category_id": "%s", "category_name": "%s", "content_classification_labels": [%s]}}}
                """.formatted(categoryId, categoryName,
                String.join(",", java.util.Arrays.stream(ccls).map(c -> "\"" + c + "\"").toList()));
    }

    @Nested
    class Lifecycle {
        @Test
        void init_connectsEveryActiveBotUser() {
            when(users.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)).thenReturn(List.of(user));

            service.init();

            verify(wsBuilder).buildAsync(any(), any());
        }

        @Test
        void accountCreation_connects_andWithoutTokenNothingOpens() {
            service.onAccountCreated(new AccountCreatedEvent(this, user));

            UserAccount tokenless = new UserAccount();
            tokenless.setId(UUID.randomUUID());
            when(tokens.findByUserAndProvider(tokenless, OAuthToken.Provider.TWITCH)).thenReturn(Optional.empty());
            service.connect(tokenless);

            verify(wsBuilder, times(1)).buildAsync(any(), any());
        }

        @Test
        void disconnect_closesTheSocket_clearsStreamState_andCancelsPendingReconnects() {
            service.connect(user);
            WebSocket.Listener listener = listeners.getFirst();
            WebSocket ws = socketOf(listener);
            listener.onClose(ws, 4000, "server going away");

            service.disconnect(user);

            verify(streamState, times(2)).clear(user);
            verify(wsBuilder, after(1500).times(1)).buildAsync(any(), any());
        }

        @Test
        void reconnectsAfterAServerClose() {
            service.connect(user);
            WebSocket.Listener listener = listeners.getFirst();

            listener.onClose(socketOf(listener), 4000, "server going away");

            verify(wsBuilder, org.mockito.Mockito.timeout(3000).times(2)).buildAsync(any(), any());
        }

        @Test
        void shutdown_closesEverySocket() {
            service.connect(user);
            WebSocket ws = socketOf(listeners.getFirst());

            service.shutdown();

            verify(ws).sendClose(WebSocket.NORMAL_CLOSURE, "application shutdown");
        }

        @Test
        void sessionReconnect_opensTheGivenUrl() {
            service.handleMessage(user, token, """
                    {"metadata": {"message_type": "session_reconnect"},
                     "payload": {"session": {"reconnect_url": "wss://eventsub.example/r"}}}""", false);

            verify(wsBuilder).buildAsync(org.mockito.ArgumentMatchers.eq(URI.create("wss://eventsub.example/r")), any());
        }
    }

    @Nested
    class Welcome {
        @Test
        void subscribes_thenLoadsTheChannelAndStreamState() {
            helix.expect(requestTo(SUBSCRIPTIONS)).andExpect(method(HttpMethod.POST))
                    .andExpect(header("Client-ID", "client-id"))
                    .andExpect(content().json("{\"type\": \"stream.online\", \"version\": \"1\","
                            + " \"condition\": {\"broadcaster_user_id\": \"b-1\"},"
                            + " \"transport\": {\"method\": \"websocket\", \"session_id\": \"s-1\"}}"))
                    .andRespond(withSuccess());
            helix.expect(requestTo(SUBSCRIPTIONS)).andExpect(content().json("{\"type\": \"stream.offline\"}"))
                    .andRespond(withSuccess());
            helix.expect(requestTo(SUBSCRIPTIONS)).andExpect(content().json("{\"type\": \"channel.update\", \"version\": \"2\"}"))
                    .andRespond(withServerError());
            json(CHANNELS, """
                    {"data": [{"game_id": "509658", "game_name": "Just Chatting", "content_classification_labels": [
                      {"id": "Gambling", "is_enabled": true}, {"id": "ProfanityVulgarity", "is_enabled": false}]}]}""");
            json(STREAMS, "{\"data\": [{\"id\": \"stream-1\"}]}");

            service.handleMessage(user, token, WELCOME, false);

            helix.verify();
            verify(streamState).setLive(user, true);

            // The loaded state is the baseline: an identical update is not a change
            service.handleMessage(user, token, channelUpdate("509658", "Just Chatting", "Gambling"), false);
            verify(publisher, never()).publishEvent(any());
        }

        @Test
        void unauthorizedSubscriptions_refreshTheTokenOnce_andUseItAfterwards() {
            helix.expect(requestTo(SUBSCRIPTIONS)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
            helix.expect(requestTo(SUBSCRIPTIONS)).andExpect(header("Authorization", "Bearer refreshed"))
                    .andRespond(withServerError());
            helix.expect(requestTo(SUBSCRIPTIONS)).andExpect(header("Authorization", "Bearer refreshed"))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
            helix.expect(requestTo(SUBSCRIPTIONS)).andExpect(header("Authorization", "Bearer refreshed"))
                    .andRespond(withSuccess());
            helix.expect(requestTo(SUBSCRIPTIONS)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
            helix.expect(requestTo(CHANNELS)).andExpect(header("Authorization", "Bearer refreshed"))
                    .andRespond(withSuccess("{\"data\": []}", MediaType.APPLICATION_JSON));
            helix.expect(requestTo(STREAMS)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
            when(tokenService.refreshAccessToken(token, user)).thenReturn("refreshed", "refreshed", null);

            service.handleMessage(user, token, WELCOME, false);

            helix.verify();
            verify(streamState, never()).setLive(any(), org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        void offlineStream_andChannelLookupFailures() {
            subscriptionsSucceed();
            helix.expect(requestTo(CHANNELS)).andRespond(withServerError());
            json(STREAMS, "{\"data\": []}");
            subscriptionsSucceed();
            helix.expect(requestTo(CHANNELS)).andRespond(withServerError());
            helix.expect(requestTo(STREAMS)).andRespond(withServerError());

            service.handleMessage(user, token, WELCOME, false);
            service.handleMessage(user, token, WELCOME, false);

            helix.verify();
            verify(streamState).setLive(user, false);
        }
    }

    @Nested
    class ChannelUpdates {
        @Test
        void theFirstUpdate_onlyRecordsTheBaseline() {
            service.handleMessage(user, token, channelUpdate("1", "Game One"), false);

            verify(publisher, never()).publishEvent(any());
        }

        @Test
        void cclChanges_arePublishedOnTheirOwn() {
            service.handleMessage(user, token, channelUpdate("1", "Game One"), false);
            service.handleMessage(user, token, channelUpdate("1", "Game One", "Gambling"), false);

            ArgumentCaptor<org.springframework.context.ApplicationEvent> event = ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
            verify(publisher).publishEvent(event.capture());
            assertThat(event.getValue()).isInstanceOfSatisfying(ChannelCclChangedEvent.class,
                    e -> assertThat(e.getCclIds()).containsExactly("Gambling"));
            verify(twitchat, never()).onCategoryChangedManually(any(), anyString(), anyString());
        }

        @Test
        void categoryChanges_arePublished_andNotifiedOnce() {
            when(selfChanges.consumeIfMatches(user, "2")).thenReturn(Optional.empty());

            service.handleMessage(user, token, channelUpdate("1", "Game One"), false);
            service.handleMessage(user, token, channelUpdate("2", "Game Two"), false);

            ArgumentCaptor<org.springframework.context.ApplicationEvent> event = ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
            verify(publisher).publishEvent(event.capture());
            assertThat(event.getValue()).isInstanceOfSatisfying(ChannelCategoryChangedEvent.class,
                    e -> assertThat(e.getCategoryName()).isEqualTo("Game Two"));
            verify(twitchat).onCategoryChangedManually(user, "2", "Game Two");
        }
    }
}
