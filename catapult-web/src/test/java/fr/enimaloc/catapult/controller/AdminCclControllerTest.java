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

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminCclController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminCclControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    @Test
    void refreshAndSave_areForwardedForAnAdmin() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminRefreshCcl()).thenReturn(204);
        when(apiService.adminSaveCclMappings(any(), any())).thenReturn(204);

        mvc.perform(post("/admin/ccl/refresh").with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/admin/ccl/Gambling/mappings").with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"igdbCategoryIds\":[6,2]}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/admin/ccl/Gambling/mappings").with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent());

        verify(apiService).adminRefreshCcl();
        verify(apiService).adminSaveCclMappings("Gambling", Set.of(6L, 2L));
        verify(apiService).adminSaveCclMappings("Gambling", Set.of());
    }

    @Test
    void relaysCatapultApisFailure() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminRefreshCcl()).thenReturn(502);
        when(apiService.adminSaveCclMappings(any(), any())).thenReturn(502);

        mvc.perform(post("/admin/ccl/refresh").with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isBadGateway());
        mvc.perform(post("/admin/ccl/Gambling/mappings").with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"igdbCategoryIds\":[6]}"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void refusedToAMember_andToAVisitor() throws Exception {
        mvc.perform(post("/admin/ccl/refresh").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/ccl/refresh").with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isForbidden());

        verify(apiService, never()).adminRefreshCcl();
        verify(apiService, never()).adminSaveCclMappings(any(), any());
    }
}
