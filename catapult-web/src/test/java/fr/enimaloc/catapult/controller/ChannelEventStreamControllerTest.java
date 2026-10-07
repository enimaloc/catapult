package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.event.BotStateChangedEvent;
import fr.enimaloc.catapult.event.binding.GameChangedEvent;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

@WebMvcTest(controllers = ChannelEventStreamController.class)
@Import(WebSecurityConfig.class)
class ChannelEventStreamControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ChannelEventStreamController controller;

    private MvcResult subscribe(String username) throws Exception {
        return mvc.perform(get("/events/channel/{username}", username).accept(MediaType.TEXT_EVENT_STREAM))
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
}
