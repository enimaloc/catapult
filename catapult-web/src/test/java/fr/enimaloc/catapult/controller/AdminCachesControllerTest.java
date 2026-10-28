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

@WebMvcTest(controllers = AdminCachesController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminCachesControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    @Test
    void delete_relaysCatapultApisStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminDeleteCacheEntry("igdb-game-cache", "a b")).thenReturn(204);
        when(apiService.adminDeleteCacheEntry("tw-known-paths", "x")).thenReturn(400);

        mvc.perform(post("/admin/caches/igdb-game-cache/delete").param("key", "a b").with(csrf())
                .sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")).andExpect(status().isNoContent());
        mvc.perform(post("/admin/caches/tw-known-paths/delete").param("key", "x").with(csrf())
                .sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")).andExpect(status().isBadRequest());
    }

    @Test
    void delete_isRefusedToAMember_andToAVisitor() throws Exception {
        mvc.perform(post("/admin/caches/igdb-game-cache/delete").param("key", "1").with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/caches/igdb-game-cache/delete").param("key", "1").with(csrf())
                .sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")).andExpect(status().isForbidden());

        verify(apiService, never()).adminDeleteCacheEntry(any(), any());
    }
}
