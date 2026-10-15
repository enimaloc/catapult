package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminDataController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminDataControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private static MockHttpServletRequestBuilder asSession(String url) {
        return post(url).with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"!hi\"}");
    }

    @Test
    void writes_relayCatapultApisStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateDataRow("chat-command", Map.of("name", "!hi"))).thenReturn(201);
        when(apiService.adminUpdateDataRow("chat-command", "a~b%20c", Map.of("name", "!hi"))).thenReturn(400);
        when(apiService.adminDeleteDataRow("chat-command", "a~b%20c")).thenReturn(409);

        mvc.perform(asSession("/admin/data/chat-command/create")).andExpect(status().isCreated());
        mvc.perform(asSession("/admin/data/chat-command/update").param("id", "a~b%20c")).andExpect(status().isBadRequest());
        mvc.perform(asSession("/admin/data/chat-command/delete").param("id", "a~b%20c")).andExpect(status().isConflict());
    }

    @Test
    void writes_areRefusedToAMember_andToAVisitor() throws Exception {
        mvc.perform(post("/admin/data/chat-command/delete").param("id", "1").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(asSession("/admin/data/chat-command/create")).andExpect(status().isForbidden());

        verify(apiService, never()).adminDeleteDataRow(any(), any());
        verify(apiService, never()).adminCreateDataRow(any(), any());
    }
}
