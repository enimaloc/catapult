package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.event.ChannelLiveStateEvent;
import fr.enimaloc.catapult.event.NavChannelsChangedEvent;
import fr.enimaloc.catapult.event.notification.BroadcastStreamEvent;
import fr.enimaloc.catapult.event.notification.NotificationStreamEvent;
import fr.enimaloc.catapult.monitoring.InteractionMetrics;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = NavEventStreamController.class)
@Import({WebSecurityConfig.class, InteractionMetrics.class, SimpleMeterRegistry.class, BroadcastState.class})
class NavEventStreamControllerTest {

    private static final ChannelDto OWN = new ChannelDto(UUID.randomUUID(), "1", "enimaloc", "img", false);
    private static final ChannelDto MODERATED = new ChannelDto(UUID.randomUUID(), "2", "other", "img", true);

    @Autowired MockMvc mvc;
    @Autowired NavEventStreamController controller;
    @Autowired MeterRegistry registry;
    @Autowired BroadcastState broadcastState;
    @MockitoBean ApiService apiService;

    private static final Instant STARTS_AT = Instant.now().plus(Duration.ofHours(1));

    private static MockHttpSession loggedIn() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "jwt");
        return session;
    }

    private MvcResult subscribe(MockHttpSession session) throws Exception {
        return mvc.perform(get("/events/nav").session(session).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
    }

    /** A logged-in session of {@code account}, with a stream open. */
    private MvcResult memberStream(MockHttpSession session, UUID account) throws Exception {
        when(apiService.currentUserId()).thenReturn(account);
        return subscribe(session);
    }

    private MvcResult anonymous(String ip) throws Exception {
        return mvc.perform(get("/events/nav").with(r -> { r.setRemoteAddr(ip); return r; })
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
    }

    private double anonymousOpen() {
        return registry.get("catapult.web.sse.streams").tag("stream", "nav-anonymous").gauge().value();
    }

    private static String sent(MvcResult stream) throws Exception {
        return stream.getResponse().getContentAsString();
    }

    private static Map<String, Object> maintenance() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("startsAt", STARTS_AT.toString());
        data.put("durationMinutes", 30);
        data.put("message", "m");
        return data;
    }

    /** The streams opened from now on, spied on so that a test can make their writes fail. */
    private final List<SseEmitter> spied = new ArrayList<>();

    private void failing() {
        controller.emitters = timeout -> {
            SseEmitter emitter = spy(new SseEmitter(timeout));
            spied.add(emitter);
            return emitter;
        };
    }

    /** The last stream opened can no longer be written to, as once its client has gone. */
    private void breakLastStream() throws IOException {
        doThrow(new IOException("gone")).when(spied.getLast()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @AfterEach
    void restoreTheStreams() {
        controller.emitters = SseEmitter::new;
    }

    @AfterEach
    void forgetTheMaintenance() {
        broadcastState.accept("maintenance.cancelled", Map.of("originalStartsAt", STARTS_AT.toString()));
    }

    /** A session whose navbar lists {@code channels}, with a stream open. */
    private MvcResult navbar(MockHttpSession session, ChannelDto... channels) throws Exception {
        controller.remember(session, List.of(channels));
        return subscribe(session);
    }

    @Test
    void aListedChannelGoingLive_flipsItInEveryNavbarListingIt() throws Exception {
        MvcResult mine = navbar(loggedIn(), OWN, MODERATED);
        MvcResult another = navbar(loggedIn(), OWN);
        MvcResult unrelated = navbar(loggedIn(), MODERATED);

        controller.onChannelLive(new ChannelLiveStateEvent("enimaloc", true));

        String event = "event:NavChannelLive\ndata:{\"username\":\"enimaloc\",\"live\":true}";
        assertThat(mine.getResponse().getContentAsString()).contains(event);
        assertThat(another.getResponse().getContentAsString()).contains(event);
        assertThat(unrelated.getResponse().getContentAsString()).doesNotContain("NavChannelLive");
    }

    @Test
    void catapultApisLiveState_findsTheChannelByItsLogin_whateverItsCase() throws Exception {
        MvcResult stream = navbar(loggedIn(), OWN, MODERATED);

        controller.onChannelLive(new ChannelLiveStateEvent("OTHER", false));
        controller.onChannelLive(new ChannelLiveStateEvent("unknown", true));

        assertThat(stream.getResponse().getContentAsString())
                .contains("data:{\"username\":\"other\",\"live\":false}")
                .containsOnlyOnce("NavChannelLive");
    }

    @Test
    void theNavbarRemembersTheLatestLiveState() throws Exception {
        MvcResult stream = navbar(loggedIn(), OWN);

        controller.onChannelLive(new ChannelLiveStateEvent("enimaloc", true));
        controller.onChannelLive(new ChannelLiveStateEvent("enimaloc", false));

        assertThat(stream.getResponse().getContentAsString())
                .contains("\"live\":true").contains("\"live\":false");
    }

    @Test
    void aChangedList_reloadsThatSessionsNavbarOnly() throws Exception {
        MockHttpSession session = loggedIn();
        MvcResult first = navbar(session, OWN);
        MvcResult second = subscribe(session);
        MvcResult other = navbar(loggedIn(), OWN);

        controller.onChannelsChanged(new NavChannelsChangedEvent(session.getId()));

        assertThat(first.getResponse().getContentAsString()).contains("event:NavChannelsChanged");
        assertThat(second.getResponse().getContentAsString()).contains("event:NavChannelsChanged");
        assertThat(other.getResponse().getContentAsString()).doesNotContain("NavChannelsChanged");
    }

    @Test
    void eventsWithoutAnOpenNavbarAreDropped() {
        controller.onChannelsChanged(new NavChannelsChangedEvent("nobody"));
        controller.onChannelLive(new ChannelLiveStateEvent("nobody", true));
    }

    @Test
    void shutdownCompletesTheOpenStreams() throws Exception {
        MvcResult stream = navbar(loggedIn(), OWN);

        controller.onContextClosed(null);

        assertThat(stream.getAsyncResult(1000)).isNull();
    }

    @Test
    void aLoggedOutVisitor_opensAStream_withoutGettingASession() throws Exception {
        MvcResult result = anonymous("1.2.3.4");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void aBroadcast_reachesMembersAndVisitorsAlike() throws Exception {
        MvcResult member = memberStream(loggedIn(), UUID.randomUUID());
        MvcResult visitor = anonymous("1.2.3.5");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", "t");
        data.put("body", "b");
        controller.onBroadcast(new BroadcastStreamEvent("alert.info", data));

        String event = "event:Broadcast\ndata:{\"name\":\"alert.info\",\"data\":{\"title\":\"t\",\"body\":\"b\"}}";
        assertThat(sent(member)).contains(event);
        assertThat(sent(visitor)).contains(event);
    }

    @Test
    void aNotification_reachesItsAccountsStreamsOnly() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        MockHttpSession aSession = loggedIn();
        MvcResult aFirst = memberStream(aSession, a);
        MvcResult aSecond = memberStream(aSession, a);
        MvcResult aElsewhere = memberStream(loggedIn(), a);
        MvcResult bStream = memberStream(loggedIn(), b);
        MvcResult nobody = memberStream(loggedIn(), null);
        MvcResult visitor = anonymous("1.2.3.6");

        controller.onNotification(new NotificationStreamEvent(a, NotificationStreamEvent.CREATED, Map.of("title", "hello")));

        String event = "event:NotificationCreated\ndata:{\"title\":\"hello\"}";
        assertThat(sent(aFirst)).contains(event);
        assertThat(sent(aSecond)).contains(event);
        assertThat(sent(aElsewhere)).contains(event);
        assertThat(sent(bStream)).doesNotContain("Notification");
        assertThat(sent(nobody)).doesNotContain("Notification");
        assertThat(sent(visitor)).doesNotContain("Notification");
    }

    @Test
    void aSessionLoggedInAsAnotherAccount_closesItsFormerStreams() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        MockHttpSession session = loggedIn();
        MvcResult asA = memberStream(session, a);
        MvcResult asB = memberStream(session, b);

        controller.onNotification(new NotificationStreamEvent(b, NotificationStreamEvent.READ, Map.of("unreadCount", 0)));

        assertThat(asA.getAsyncResult(1000)).isNull(); // completed
        assertThat(sent(asA)).doesNotContain("Notification");
        assertThat(sent(asB)).contains("event:NotificationRead");
    }

    /** An impersonation's swap: the former login's navbar channels and streams are gone, another session's kept. */
    @Test
    void forget_dropsTheSessionsChannels_andClosesItsStreams() throws Exception {
        UUID a = UUID.randomUUID();
        MockHttpSession session = loggedIn();
        MockHttpSession other = loggedIn();
        controller.remember(session, List.of(OWN));
        MvcResult stream = memberStream(session, a);
        MvcResult kept = memberStream(other, a);

        controller.forget(session);

        assertThat(stream.getAsyncResult(1000)).isNull(); // completed
        assertThat(SessionChannels.of(session)).isEmpty();
        controller.onNotification(new NotificationStreamEvent(a, NotificationStreamEvent.READ, Map.of("unreadCount", 0)));
        assertThat(sent(kept)).contains("event:NotificationRead");
    }

    @Test
    void aLoggedOutSession_getsNoMoreNotifications() throws Exception {
        UUID a = UUID.randomUUID();
        MockHttpSession session = loggedIn();
        MvcResult stream = memberStream(session, a);

        session.invalidate();
        controller.onNotification(new NotificationStreamEvent(a, NotificationStreamEvent.CREATED, Map.of("title", "x")));

        assertThat(stream.getAsyncResult(1000)).isNull();
        assertThat(sent(stream)).doesNotContain("Notification");
    }

    @Test
    void aSessionLoggedInAgainAsAnotherAccount_closesTheFormerAccountsStreams() throws Exception {
        UUID a = UUID.randomUUID();
        MockHttpSession session = loggedIn();
        MvcResult stream = memberStream(session, a);

        session.setAttribute(ApiClient.SESSION_JWT_KEY, "jwt-of-b"); // AuthController.callback keeps the session
        controller.onNotification(new NotificationStreamEvent(a, NotificationStreamEvent.CREATED, Map.of("title", "x")));

        assertThat(stream.getAsyncResult(1000)).isNull();
        assertThat(sent(stream)).doesNotContain("Notification");
    }

    @Test
    void aVisitorStreamThatCannotBeWritten_freesItsSlot() throws Exception {
        failing();
        anonymous("5.5.5.5");
        double before = anonymousOpen();

        breakLastStream();
        controller.onBroadcast(new BroadcastStreamEvent("alert.info", Map.of("title", "t")));

        assertThat(anonymousOpen()).isEqualTo(before - 1);
    }

    @Test
    void theStreamAsksProxiesNotToBufferIt() throws Exception {
        assertThat(anonymous("1.2.3.10").getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(memberStream(loggedIn(), UUID.randomUUID()).getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
    }

    @Test
    void aNewStream_getsAHeartbeatAtOnce_soThatItsHeadersGoOutAndThePageSeesItOpen() throws Exception {
        assertThat(sent(anonymous("1.2.3.12"))).isEqualTo(":heartbeat\n\n");
        assertThat(sent(memberStream(loggedIn(), UUID.randomUUID()))).isEqualTo(":heartbeat\n\n");
    }

    @Test
    void aHeartbeat_reachesMembersAndVisitorsAlike_asAComment() throws Exception {
        MvcResult member = memberStream(loggedIn(), UUID.randomUUID());
        MvcResult visitor = anonymous("1.2.3.11");

        controller.heartbeat();

        assertThat(sent(member)).contains(":heartbeat\n\n");
        assertThat(sent(visitor)).contains(":heartbeat\n\n");
    }

    @Test
    void aVisitorStreamThatCannotTakeAHeartbeat_freesItsSlot() throws Exception {
        failing();
        anonymous("5.5.5.6");
        double before = anonymousOpen();

        breakLastStream();
        controller.heartbeat();

        assertThat(anonymousOpen()).isEqualTo(before - 1);
    }

    @Test
    void aMemberStreamThatCannotTakeAHeartbeat_freesItsSlot() throws Exception {
        failing();
        memberStream(loggedIn(), UUID.randomUUID());
        double before = registry.get("catapult.web.sse.streams").tag("stream", "nav").gauge().value();

        breakLastStream();
        controller.heartbeat();

        assertThat(registry.get("catapult.web.sse.streams").tag("stream", "nav").gauge().value()).isEqualTo(before - 1);
    }

    @Test
    void aRememberedMaintenance_isTheFirstThingANewStreamReceives() throws Exception {
        MvcResult before = anonymous("1.2.3.7");
        assertThat(sent(before)).doesNotContain("maintenance");

        controller.onBroadcast(new BroadcastStreamEvent("maintenance.scheduled", maintenance()));
        MvcResult member = memberStream(loggedIn(), UUID.randomUUID());
        MvcResult visitor = anonymous("1.2.3.8");

        String event = "event:Broadcast\ndata:{\"name\":\"maintenance.scheduled\",\"data\":{\"startsAt\":\""
                + STARTS_AT + "\",\"durationMinutes\":30,\"message\":\"m\"}}";
        assertThat(sent(member)).startsWith(event);
        assertThat(sent(visitor)).startsWith(event);
        assertThat(sent(before)).contains(event); // live, as any broadcast
    }

    @Test
    void shutdownWarnsEveryStreamBeforeClosingIt() throws Exception {
        MvcResult member = memberStream(loggedIn(), UUID.randomUUID());
        MvcResult visitor = anonymous("1.2.3.9");

        controller.onContextClosed(null);

        String event = "event:Broadcast\ndata:{\"name\":\"maintenance.imminent\",\"data\":{\"etaSeconds\":null}}";
        assertThat(member.getAsyncResult(1000)).isNull();
        assertThat(visitor.getAsyncResult(1000)).isNull();
        assertThat(sent(member)).contains(event);
        assertThat(sent(visitor)).contains(event);
    }

    @Test
    void anAddressKeepsAtMostAFewAnonymousStreams_closingItsOldest() throws Exception {
        MvcResult oldest = anonymous("4.4.4.4");
        for (int i = 1; i < AnonymousStreams.MAX_PER_IP; i++) anonymous("4.4.4.4");
        MvcResult newest = anonymous("4.4.4.4");

        controller.onBroadcast(new BroadcastStreamEvent("alert.info", Map.of("title", "t")));

        assertThat(oldest.getAsyncResult(1000)).isNull();
        assertThat(sent(oldest)).endsWith("event:Evicted\ndata:{}\n\n"); // told why, so it does not reconnect at once
        assertThat(sent(oldest)).doesNotContain("alert.info");
        assertThat(sent(newest)).contains("alert.info");
        assertThat(sent(newest)).doesNotContain("Evicted");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void pastTheOverallLimit_anonymousStreamsAreRefused() throws Exception {
        int open = (int) registry.get("catapult.web.sse.streams").tag("stream", "nav-anonymous").gauge().value();
        for (int i = open; i < AnonymousStreams.MAX_TOTAL; i++) anonymous("10.1." + i / 250 + "." + i % 250);

        mvc.perform(get("/events/nav").with(r -> { r.setRemoteAddr("9.9.9.9"); return r; })
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isServiceUnavailable());
        assertThat(registry.get("catapult.web.sse.refused").tags("stream", "nav-anonymous", "reason", "capacity")
                .counter().count()).isPositive();
    }

    @Test
    void aSessionKeepsAtMostAFewStreams_closingItsOldest() throws Exception {
        MockHttpSession session = loggedIn();
        controller.remember(session, List.of(OWN));
        MvcResult oldest = subscribe(session);
        for (int i = 1; i < SessionStreams.MAX_PER_SESSION; i++) subscribe(session);
        MvcResult newest = subscribe(session);

        controller.onChannelLive(new ChannelLiveStateEvent("enimaloc", true));

        assertThat(oldest.getAsyncResult(1000)).isNull(); // completed
        assertThat(sent(oldest)).endsWith("event:Evicted\ndata:{}\n\n");
        assertThat(oldest.getResponse().getContentAsString()).doesNotContain("NavChannelLive");
        assertThat(newest.getResponse().getContentAsString()).contains("NavChannelLive");
    }

    @Test
    void theListIsKeptInTheSession_andGoesWithIt() throws Exception {
        MockHttpSession session = loggedIn();
        MvcResult stream = navbar(session, OWN);
        assertThat(SessionChannels.of(session)).isEqualTo(List.of(OWN));

        session.invalidate();
        controller.onChannelLive(new ChannelLiveStateEvent("enimaloc", true));

        assertThat(stream.getAsyncResult(1000)).isNull();
        assertThat(stream.getResponse().getContentAsString()).doesNotContain("NavChannelLive");
    }
}
