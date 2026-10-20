package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.cluster.InstanceBus;
import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.event.ChannelLiveStateEvent;
import fr.enimaloc.catapult.monitoring.InteractionMetrics;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
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
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * What a navbar lists, as told through the {@link InstanceBus}: the session's stream may be open on
 * another instance than the one serving the request that changed its list.
 */
@WebMvcTest(controllers = NavEventStreamController.class)
@Import({WebSecurityConfig.class, InteractionMetrics.class, SimpleMeterRegistry.class, BroadcastState.class, DirectSse.class,
        InstanceBus.class})
class NavEventStreamClusterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ChannelDto OWN = new ChannelDto(UUID.randomUUID(), "1", "enimaloc", "img", false);

    @Autowired MockMvc mvc;
    @Autowired NavEventStreamController controller;
    @Autowired InstanceBus bus;
    @MockitoBean ApiService apiService;

    private static MockHttpSession loggedIn() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "jwt");
        return session;
    }

    private MvcResult subscribe(MockHttpSession session) throws Exception {
        return mvc.perform(get("/events/nav").session(session).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    /** What another instance tells this one when a request it served changed {@code sessionId}'s navbar. */
    private void toldByAnotherInstance(String sessionId, List<ChannelDto> channels) {
        bus.publish(NavEventStreamController.REMEMBER_TOPIC,
                JSON.writeValueAsString(new NavEventStreamController.Remembered(sessionId, channels)));
    }

    @Test
    void aListRemembered_reachesTheSessionsOpenNavbar_throughTheBus() throws Exception {
        MockHttpSession session = loggedIn();
        MvcResult stream = subscribe(session);

        controller.remember(session, List.of(OWN));
        controller.onChannelLive(new ChannelLiveStateEvent("enimaloc", true));

        assertThat(SessionChannels.of(session)).containsExactly(OWN);
        assertThat(stream.getResponse().getContentAsString())
                .contains("event:NavChannelLive\ndata:{\"username\":\"enimaloc\",\"live\":true}");
    }

    @Test
    void anotherInstancesList_forASessionOpenHere_isWhatItsNavbarNowFollows() throws Exception {
        MockHttpSession session = loggedIn();
        MvcResult stream = subscribe(session);
        MvcResult unrelated = subscribe(loggedIn());

        toldByAnotherInstance(session.getId(), List.of(OWN));
        toldByAnotherInstance("not-open-here", List.of(OWN));
        controller.onChannelLive(new ChannelLiveStateEvent("enimaloc", true));

        assertThat(stream.getResponse().getContentAsString()).contains("NavChannelLive");
        assertThat(unrelated.getResponse().getContentAsString()).doesNotContain("NavChannelLive");
    }

    @Test
    void anotherInstancesEmptiedList_orGarbage_leaveNothingListed() throws Exception {
        MockHttpSession session = loggedIn();
        controller.remember(session, List.of(OWN));
        MvcResult stream = subscribe(session);

        bus.publish(NavEventStreamController.REMEMBER_TOPIC, "{oops");
        bus.publish(NavEventStreamController.REMEMBER_TOPIC, "{\"sessionId\":\"" + session.getId() + "\"}");
        controller.onChannelLive(new ChannelLiveStateEvent("enimaloc", true));

        assertThat(stream.getResponse().getContentAsString()).doesNotContain("NavChannelLive");
    }
}
