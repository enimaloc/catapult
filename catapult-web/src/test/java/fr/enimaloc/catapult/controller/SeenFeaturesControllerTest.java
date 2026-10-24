package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SeenFeaturesController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class SeenFeaturesControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    @Test
    void seen_forwardsAKnownKeyWithCatapultApisStatus() throws Exception {
        when(apiService.markFeatureSeen("channel.commands")).thenReturn(204);

        mvc.perform(post("/spa/seen-features/channel.commands").with(csrf())
                        .sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isNoContent());
    }

    @Test
    void seen_refusesAKeyNoTabIsMarkedWith() throws Exception {
        mvc.perform(post("/spa/seen-features/channel.unknown").with(csrf())
                        .sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isNotFound());
        verify(apiService, never()).markFeatureSeen(any());
    }

    @Test
    void seen_isRefusedToAVisitor() throws Exception {
        mvc.perform(post("/spa/seen-features/channel.commands").with(csrf()))
                .andExpect(status().isUnauthorized());
        verify(apiService, never()).markFeatureSeen(any());
    }
}
