package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.event.BotStateChangedEvent;
import fr.enimaloc.catapult.event.binding.GameChangedEvent;
import fr.enimaloc.catapult.monitoring.InteractionMetrics;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ChannelEventStreamController.class)
@Import({WebSecurityConfig.class, InteractionMetrics.class, SimpleMeterRegistry.class, DirectSse.class})
class ChannelEventStreamControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ChannelEventStreamController controller;
    @Autowired MeterRegistry registry;
    @MockitoBean ApiService apiService;

    private MvcResult subscribe(String username) throws Exception {
        return subscribe(username, loggedIn());
    }

    /** A logged-in session whose member can act on {@code usernames} (by default, every channel these tests follow). */
    private static MockHttpSession loggedIn(String... usernames) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "jwt");
        List<String> names = usernames.length > 0 ? List.of(usernames) : List.of("enimaloc", "closing");
        SessionChannels.remember(session, names.stream().map(ChannelEventStreamControllerTest::channel).toList());
        return session;
    }

    private static ChannelDto channel(String username) {
        return new ChannelDto(UUID.randomUUID(), "1", username, "img", false);
    }

    private MvcResult subscribe(String username, MockHttpSession session) throws Exception {
        return mvc.perform(get("/events/channel/{username}", username).session(session).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
    }

    @Test
    void streamsEachChannelsEventsNamedAfterTheirType() throws Exception {
        MvcResult stream = subscribe("enimaloc");

        controller.onChannelUpdated(new GameChangedEvent("enimaloc", "b1", "STEAM", "Valheim"));
        controller.onChannelUpdated(new BotStateChangedEvent("enimaloc", false));

        String body = stream.getResponse().getContentAsString();
        assertThat(body)
                .contains("event:GameChangedEvent\ndata:{\"username\":\"enimaloc\",\"bindingId\":\"b1\",\"sourceType\":\"STEAM\",\"sourceName\":\"Valheim\"}")
                .contains("event:BotStateChangedEvent\ndata:{\"username\":\"enimaloc\",\"state\":false}");
    }

    @Test
    void aChannelIsFollowedByItsLogin_whateverItsCase() throws Exception {
        MvcResult stream = subscribe("EnimaLoc");

        controller.onChannelUpdated(new BotStateChangedEvent("enimaloc", true));

        assertThat(stream.getResponse().getContentAsString()).contains("BotStateChangedEvent");
        assertThat(stream.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
    }

    @Test
    void otherChannelsEventsAreNotSent() throws Exception {
        MvcResult stream = subscribe("enimaloc");

        controller.onChannelUpdated(new BotStateChangedEvent("someone-else", true));

        assertThat(stream.getResponse().getContentAsString()).doesNotContain("BotStateChangedEvent");
    }

    @Test
    void everyOpenStreamOfTheChannelReceivesTheEvent() throws Exception {
        MvcResult first = subscribe("enimaloc");
        MvcResult second = subscribe("enimaloc");

        controller.onChannelUpdated(new BotStateChangedEvent("enimaloc", true));

        assertThat(first.getResponse().getContentAsString()).contains("BotStateChangedEvent");
        assertThat(second.getResponse().getContentAsString()).contains("BotStateChangedEvent");
    }

    @Test
    void eventsWithoutSubscribersAreDropped() {
        controller.onChannelUpdated(new BotStateChangedEvent("nobody", true));
    }

    @Test
    void shutdownCompletesTheOpenStreams() throws Exception {
        MvcResult stream = subscribe("closing");

        controller.onContextClosed(null);

        assertThat(stream.getAsyncResult(1000)).isNull();
    }

    @Test
    void aLoggedOutVisitor_cannotOpenAStream_norGetsASession() throws Exception {
        MvcResult result = mvc.perform(get("/events/channel/{username}", "enimaloc").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized()).andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(registry.get("catapult.web.sse.refused").tags("stream", "channel", "reason", "unauthenticated")
                .counter().count()).isPositive();
    }

    @Test
    void aSessionKeepsAtMostAFewStreams_closingItsOldest() throws Exception {
        MockHttpSession session = loggedIn();
        double evicted = evicted();
        MvcResult oldest = subscribe("enimaloc", session);
        for (int i = 1; i < SessionStreams.MAX_PER_SESSION; i++) subscribe("enimaloc", session);
        MvcResult newest = subscribe("enimaloc", session);
        assertThat(evicted()).isEqualTo(evicted + 1);

        controller.onChannelUpdated(new BotStateChangedEvent("enimaloc", true));

        assertThat(oldest.getAsyncResult(1000)).isNull();
        assertThat(oldest.getResponse().getContentAsString()).doesNotContain("BotStateChangedEvent");
        assertThat(newest.getResponse().getContentAsString()).contains("BotStateChangedEvent");
    }

    private double evicted() {
        var counter = registry.find("catapult.web.sse.evicted").tag("stream", "channel").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void openStreams_areGauged() throws Exception {
        double before = registry.get("catapult.web.sse.streams").tag("stream", "channel").gauge().value();
        subscribe("enimaloc", loggedIn());

        assertThat(registry.get("catapult.web.sse.streams").tag("stream", "channel").gauge().value()).isEqualTo(before + 1);
    }

    @Test
    void aChannelTheMemberNeitherOwnsNorModerates_cannotBeFollowed() throws Exception {
        when(apiService.channelList()).thenReturn(new ChannelListResponse("1", List.of(channel("enimaloc"))));

        mvc.perform(get("/events/channel/{username}", "someone-else").session(loggedIn("enimaloc"))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());
        assertThat(registry.get("catapult.web.sse.refused").tags("stream", "channel", "reason", "forbidden")
                .counter().count()).isPositive();
    }

    @Test
    void aChannelMissingFromTheSessionsList_isCheckedWithTheApi_thenKept() throws Exception {
        MockHttpSession session = loggedIn("enimaloc");
        when(apiService.channelList()).thenReturn(new ChannelListResponse("1", List.of(channel("enimaloc"), channel("newly-moderated"))));

        subscribe("Newly-Moderated", session);
        subscribe("newly-moderated", session);

        verify(apiService, times(1)).channelList();
        assertThat(SessionChannels.of(session)).extracting(ChannelDto::twitchUsername).contains("newly-moderated");
    }

    @Test
    void anApiThatFails_grantsNothing() throws Exception {
        when(apiService.channelList()).thenReturn(null);

        mvc.perform(get("/events/channel/{username}", "someone-else").session(loggedIn("enimaloc"))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());
    }
}
