package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.event.ChannelLiveStateEvent;
import fr.enimaloc.catapult.event.NavChannelsChangedEvent;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = NavEventStreamController.class)
@Import(WebSecurityConfig.class)
class NavEventStreamControllerTest {

    private static final ChannelDto OWN = new ChannelDto(UUID.randomUUID(), "1", "enimaloc", "img", false);
    private static final ChannelDto MODERATED = new ChannelDto(UUID.randomUUID(), "2", "other", "img", true);

    @Autowired MockMvc mvc;
    @Autowired NavEventStreamController controller;

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
    void catapultApisLiveState_findsTheChannelByItsId() throws Exception {
        MvcResult stream = navbar(loggedIn(), OWN, MODERATED);

        controller.liveChanged(MODERATED.id(), false);
        controller.liveChanged(UUID.randomUUID(), true);

        assertThat(stream.getResponse().getContentAsString())
                .contains("data:{\"username\":\"other\",\"live\":false}")
                .containsOnlyOnce("NavChannelLive");
    }

    @Test
    void theNavbarRemembersTheLatestLiveState() throws Exception {
        MvcResult stream = navbar(loggedIn(), OWN);

        controller.onChannelLive(new ChannelLiveStateEvent("enimaloc", true));
        controller.liveChanged(OWN.id(), false);

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
    void aLoggedOutVisitor_cannotOpenAStream_norGetsASession() throws Exception {
        MvcResult result = mvc.perform(get("/events/nav").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized()).andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
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
