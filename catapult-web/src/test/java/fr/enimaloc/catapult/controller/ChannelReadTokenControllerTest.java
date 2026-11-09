package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.account.ApiTokenCreatedDto;
import fr.enimaloc.catapult.common.dto.account.ApiTokenStatusDto;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ChannelReadTokenController.class)
@Import(WebSecurityConfig.class)
class ChannelReadTokenControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;

    @Test
    void status_isRelayed_orBadGatewayWithoutAnAnswer() throws Exception {
        when(apiService.readApiTokenStatus()).thenReturn(new ApiTokenStatusDto(true, Instant.parse("2026-01-01T00:00:00Z")));
        mvc.perform(get("/me/api-token/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true));

        when(apiService.readApiTokenStatus()).thenReturn(null);
        mvc.perform(get("/me/api-token/read")).andExpect(status().isBadGateway());
    }

    /** The new token, shown once, and the chat bots' summary link built on it: never cacheable. */
    @Test
    void regenerate_showsTheTokenOnce_withTheSummaryUrlBuiltOnIt() throws Exception {
        when(apiService.regenerateReadApiToken()).thenReturn(
                new ApiClient.Result<>(200, new ApiTokenCreatedDto("catr_secret", Instant.parse("2026-01-01T00:00:00Z"))));
        mvc.perform(post("/me/api-token/read").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.token").value("catr_secret"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.summaryUrl").value("http://localhost/api/me/game/summary?token=catr_secret"));

        when(apiService.regenerateReadApiToken()).thenReturn(new ApiClient.Result<>(401, null));
        mvc.perform(post("/me/api-token/read").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void regenerate_needsTheCsrfToken() throws Exception {
        mvc.perform(post("/me/api-token/read")).andExpect(status().isForbidden());
    }

    @Test
    void revoke_answersCatapultApisStatus() throws Exception {
        when(apiService.revokeReadApiToken()).thenReturn(204);
        mvc.perform(post("/me/api-token/read/delete").with(csrf())).andExpect(status().isNoContent());
        when(apiService.revokeReadApiToken()).thenReturn(502);
        mvc.perform(post("/me/api-token/read/delete").with(csrf())).andExpect(status().isBadGateway());
    }
}
