package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.EpicAccountDto;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.ApiService.AdminCall;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminEpicController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminEpicControllerTest {
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final String BASE = "/admin/epic-accounts";
    static final String CODE = "0123456789abcdef0123456789abcdef";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static EpicAccountDto account() {
        return new EpicAccountDto(ID, "Bot", "READY", "READY", 0, false, true, 3,
                Instant.parse("2027-01-15T00:00:00Z"), false);
    }

    @Test
    void addRelaysTheAccount_trimmingWhatWasPasted_forANewBotAndForAReauth() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminAddEpicAccount(CODE, null)).thenReturn(new AdminCall<>(200, account(), null));
        when(apiService.adminAddEpicAccount(CODE, ID)).thenReturn(new AdminCall<>(404, null, null));

        mvc.perform(admin(post(BASE), "{\"code\":\" " + CODE + " \"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Bot")).andExpect(jsonPath("$.authState").value("READY"));
        mvc.perform(admin(post(BASE), "{\"code\":\"" + CODE + "\",\"accountId\":\"" + ID + "\"}"))
                .andExpect(status().isNotFound());
        verify(apiService).adminAddEpicAccount(CODE, ID);
    }

    @Test
    void addRelaysTheApisRefusalMessageAsIs() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminAddEpicAccount("bad", null))
                .thenReturn(new AdminCall<>(400, null, "this code expired or was already used, get a new one"));

        mvc.perform(admin(post(BASE), "{\"code\":\"bad\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("this code expired or was already used, get a new one"));
    }

    @Test
    void aBlankOrMissingCodeIsRefusedWithoutCallingTheApi() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(admin(post(BASE), "{\"code\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE), "{}")).andExpect(status().isBadRequest());
        verify(apiService, never()).adminAddEpicAccount(any(), any());
    }

    @Test
    void enabledResetLimitAndDetailsPatchTheAccount() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminPatchEpicAccount(any(), any())).thenReturn(new AdminCall<>(200, account(), null));
        when(apiService.adminPatchEpicAccount(ID, Map.of("friendLimitReached", false))).thenReturn(new AdminCall<>(404, null, null));

        mvc.perform(admin(post(BASE + "/" + ID + "/enabled"), "{\"enabled\":false}")).andExpect(status().isOk());
        verify(apiService).adminPatchEpicAccount(ID, Map.of("enabled", false));
        mvc.perform(admin(post(BASE + "/" + ID + "/enabled"), "{}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/" + ID + "/reset-limit"), "{}")).andExpect(status().isNotFound());
        mvc.perform(admin(post(BASE + "/" + ID + "/details"), "{\"fillOrder\":3}")).andExpect(status().isOk());
        verify(apiService).adminPatchEpicAccount(ID, Map.of("fillOrder", 3));
        mvc.perform(admin(post(BASE + "/" + ID + "/details"), "{\"fillOrder\":-1}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/" + ID + "/details"), "{}")).andExpect(status().isBadRequest());
    }

    @Test
    void deleteRelaysTheStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminDeleteEpicAccount(ID)).thenReturn(204);

        mvc.perform(admin(post(BASE + "/" + ID + "/delete"), "{}")).andExpect(status().isNoContent());
    }

    @Test
    void nonAdminIsRefusedBeforeTheApiIsCalled() throws Exception {
        when(apiService.isAdmin()).thenReturn(false);

        mvc.perform(admin(post(BASE), "{\"code\":\"" + CODE + "\"}")).andExpect(status().is4xxClientError());
        mvc.perform(admin(post(BASE + "/" + ID + "/delete"), "{}")).andExpect(status().is4xxClientError());
        verify(apiService, never()).adminAddEpicAccount(any(), any());
        verify(apiService, never()).adminDeleteEpicAccount(any());
    }
}
