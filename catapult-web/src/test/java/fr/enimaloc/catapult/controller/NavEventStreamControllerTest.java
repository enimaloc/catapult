package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.event.ChannelLiveStateEvent;
import fr.enimaloc.catapult.event.NavChannelsChangedEvent;
import fr.enimaloc.catapult.security.WebSecurityConfig;
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

@WebMvcTest(controllers = NavEventStreamController.class)
@Import(WebSecurityConfig.class)
class NavEventStreamControllerTest {

    private static final ChannelDto OWN = new ChannelDto(UUID.randomUUID(), "1", "enimaloc", "img", false);
    private static final ChannelDto MODERATED = new ChannelDto(UUID.randomUUID(), "2", "other", "img", true);

    @Autowired MockMvc mvc;
    @Autowired NavEventStreamController controller;

    private MvcResult subscribe(MockHttpSession session) throws Exception {
        return mvc.perform(get("/events/nav").session(session).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
    }

    /** A session whose navbar lists {@code channels}, with a stream open. */
    private MvcResult navbar(MockHttpSession session, ChannelDto... channels) throws Exception {
        controller.remember(session.getId(), List.of(channels));
        return subscribe(session);
    }

    @Test
    void aListedChannelGoingLive_flipsItInEveryNavbarListingIt() throws Exception {
        MvcResult mine = navbar(new MockHttpSession(), OWN, MODERATED);
        MvcResult another = navbar(new MockHttpSession(), OWN);
        MvcResult unrelated = navbar(new MockHttpSession(), MODERATED);

        controller.onChannelLive(new ChannelLiveStateEvent("enimaloc", true));

        String event = "event:NavChannelLive\ndata:{\"username\":\"enimaloc\",\"live\":true}";
        assertThat(mine.getResponse().getContentAsString()).contains(event);
        assertThat(another.getResponse().getContentAsString()).contains(event);
        assertThat(unrelated.getResponse().getContentAsString()).doesNotContain("NavChannelLive");
    }

    @Test
    void catapultApisLiveState_findsTheChannelByItsId() throws Exception {
        MvcResult stream = navbar(new MockHttpSession(), OWN, MODERATED);

        controller.liveChanged(MODERATED.id(), false);
        controller.liveChanged(UUID.randomUUID(), true);

        assertThat(stream.getResponse().getContentAsString())
                .contains("data:{\"username\":\"other\",\"live\":false}")
                .containsOnlyOnce("NavChannelLive");
    }

    @Test
    void theNavbarRemembersTheLatestLiveState() throws Exception {
        MvcResult stream = navbar(new MockHttpSession(), OWN);

        controller.onChannelLive(new ChannelLiveStateEvent("enimaloc", true));
        controller.liveChanged(OWN.id(), false);

        assertThat(stream.getResponse().getContentAsString())
                .contains("\"live\":true").contains("\"live\":false");
    }

    @Test
    void aChangedList_reloadsThatSessionsNavbarOnly() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult first = navbar(session, OWN);
        MvcResult second = subscribe(session);
        MvcResult other = navbar(new MockHttpSession(), OWN);

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
        MvcResult stream = navbar(new MockHttpSession(), OWN);

        controller.onContextClosed(null);

        assertThat(stream.getAsyncResult(1000)).isNull();
    }
}
