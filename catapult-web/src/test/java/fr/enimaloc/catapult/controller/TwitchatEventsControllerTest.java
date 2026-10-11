package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatValidationError;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TwitchatEventsController.class)
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class})
class TwitchatEventsControllerTest {

    private static final String LIST = "[{\"action\":\"SEND_MESSAGE\",\"mode\":\"FORM\",\"dataJson\":\"{\\\"message\\\":\\\"hi\\\"}\",\"enabled\":true}]";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;

    @Test
    void save_relaysToTheApi() throws Exception {
        when(apiService.saveTwitchatEvent(eq("streamer"), eq("STREAM_STARTED"), any())).thenReturn(List.of());

        mvc.perform(post("/channel/streamer/twitchat/events/STREAM_STARTED").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(LIST))
                .andExpect(status().isNoContent());

        verify(apiService).saveTwitchatEvent("streamer", "STREAM_STARTED",
                List.of(new TwitchatEventActionDto("SEND_MESSAGE", "FORM", "{\"message\":\"hi\"}", true)));
    }

    @Test
    void save_invalid_answers400WithTheErrors() throws Exception {
        when(apiService.saveTwitchatEvent(any(), any(), any())).thenReturn(List.of(new TwitchatValidationError(0, "message", "Required")));

        mvc.perform(post("/channel/streamer/twitchat/events/STREAM_STARTED").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(LIST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].field").value("message"));
    }

    @Test
    void payloadForm_newPayload_rendersItsCard() throws Exception {
        mvc.perform(post("/channel/streamer/twitchat/payload-form").param("event", "STREAM_STARTED").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"COUNTER_ADD\",\"mode\":\"FORM\",\"dataJson\":null,\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("twitchat-payload")))
                .andExpect(content().string(containsString("data-field=\"value\"")));
    }

    @Test
    void payloadForm_backToAFormTheJsonNoLongerFits_is422() throws Exception {
        mvc.perform(post("/channel/streamer/twitchat/payload-form").param("event", "STREAM_STARTED").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"SEND_MESSAGE\",\"mode\":\"FORM\",\"dataJson\":\"{\\\"message\\\":42}\",\"enabled\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$[0].field").value("message"));
    }

    @Test
    void payloadForm_backToAFormThatWouldDropKeys_is422() throws Exception {
        mvc.perform(post("/channel/streamer/twitchat/payload-form").param("event", "STREAM_STARTED").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"CUSTOM_CHAT_MESSAGE\",\"mode\":\"FORM\",\"dataJson\":\"{\\\"message\\\":\\\"Hi\\\",\\\"user\\\":{\\\"name\\\":\\\"Bot\\\",\\\"color\\\":\\\"#f00\\\"}}\",\"enabled\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$[0].field").value("user.color"))
                .andExpect(jsonPath("$[0].message").value("Not in the form"));
    }

    @Test
    void payloadForm_backToAFormThatHoldsItAll_rendersTheCard() throws Exception {
        mvc.perform(post("/channel/streamer/twitchat/payload-form").param("event", "STREAM_STARTED").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"CUSTOM_CHAT_MESSAGE\",\"mode\":\"FORM\",\"dataJson\":\"{\\\"message\\\":\\\"Hi\\\",\\\"user\\\":{\\\"name\\\":\\\"Bot\\\"}}\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-field=\"user.name\"")));
    }
}
