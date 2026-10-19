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

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminWhitelistController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminWhitelistControllerTest {
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    static final String BASE = "/admin/whitelist";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void togglesAndEntryActions_relayTheirStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminToggleWhitelist()).thenReturn(204);
        when(apiService.adminToggleInvites()).thenReturn(502);
        when(apiService.adminAddWhitelist("123")).thenReturn(204);
        when(apiService.adminDeleteWhitelist("456")).thenReturn(204);

        mvc.perform(admin(post(BASE + "/toggle"), "{}")).andExpect(status().isNoContent());
        mvc.perform(admin(post(BASE + "/invite/toggle"), "{}")).andExpect(status().isBadGateway());
        mvc.perform(admin(post(BASE + "/add"), "{\"twitchId\":\" 123 \"}")).andExpect(status().isNoContent());
        mvc.perform(admin(post(BASE + "/456/delete"), "{}")).andExpect(status().isNoContent());
        verify(apiService).adminAddWhitelist("123");
        verify(apiService).adminDeleteWhitelist("456");
    }

    @Test
    void add_andDelete_refuseANonDigitId_withoutCallingTheApi() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(admin(post(BASE + "/add"), "{\"twitchId\":\"abc\"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/add"), "{\"twitchId\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/add"), "{}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/abc/delete"), "{}")).andExpect(status().isBadRequest());
        verify(apiService, never()).adminAddWhitelist(any());
        verify(apiService, never()).adminDeleteWhitelist(any());
    }

    @Test
    void settings_relayNullsAndFlag_andRefuseNegatives() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminSaveInviteSettings(null, 3, false)).thenReturn(204);
        when(apiService.adminSaveInviteSettings(10, null, true)).thenReturn(204);

        mvc.perform(admin(post(BASE + "/invite/settings"), "{\"globalMaxMembers\":null,\"defaultMaxUses\":3,\"defaultCanReinvite\":false}"))
                .andExpect(status().isNoContent());
        mvc.perform(admin(post(BASE + "/invite/settings"), "{\"globalMaxMembers\":10,\"defaultMaxUses\":null,\"defaultCanReinvite\":true}"))
                .andExpect(status().isNoContent());
        mvc.perform(admin(post(BASE + "/invite/settings"), "{\"globalMaxMembers\":0,\"defaultMaxUses\":3,\"defaultCanReinvite\":false}"))
                .andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/invite/settings"), "{\"defaultMaxUses\":-1}")).andExpect(status().isBadRequest());
        verify(apiService, org.mockito.Mockito.times(2)).adminSaveInviteSettings(any(), any(), anyBoolean());
    }

    @Test
    void quota_relaysItsStatus_andRefusesANegative() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminSaveInviteQuota(ID, null, true)).thenReturn(204);
        when(apiService.adminSaveInviteQuota(ID, 0, null)).thenReturn(404);

        mvc.perform(admin(post(BASE + "/invite/" + ID + "/quota"), "{\"maxUses\":null,\"canReinvite\":true}")).andExpect(status().isNoContent());
        mvc.perform(admin(post(BASE + "/invite/" + ID + "/quota"), "{\"maxUses\":0}")).andExpect(status().isNotFound());
        mvc.perform(admin(post(BASE + "/invite/" + ID + "/quota"), "{\"maxUses\":-2}")).andExpect(status().isBadRequest());
    }

    @Test
    void actions_refuseVisitorsAndMembers_andNeedCsrf() throws Exception {
        String[] urls = {BASE + "/toggle", BASE + "/add", BASE + "/123/delete", BASE + "/invite/toggle",
                BASE + "/invite/settings", BASE + "/invite/" + ID + "/quota"};
        for (String url : urls) {
            mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(admin(post(url), "{}")).andExpect(status().isForbidden());
            mvc.perform(post(url).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        }
        // an admin without a CSRF token is refused too
        when(apiService.isAdmin()).thenReturn(true);
        for (String url : urls) {
            mvc.perform(post(url).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"twitchId\":\"123\"}")).andExpect(status().isForbidden());
        }
        verify(apiService, never()).adminToggleWhitelist();
        verify(apiService, never()).adminAddWhitelist(any());
        verify(apiService, never()).adminDeleteWhitelist(any());
        verify(apiService, never()).adminToggleInvites();
        verify(apiService, never()).adminSaveInviteSettings(any(), any(), anyBoolean());
        verify(apiService, never()).adminSaveInviteQuota(any(), any(), any());
    }
}
