package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.AnkamaAccountDto;
import fr.enimaloc.catapult.common.dto.admin.AnkamaLoginStartDto;
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

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminAnkamaController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminAnkamaControllerTest {
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID LOGIN = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    static final String BASE = "/admin/ankama-accounts";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static AnkamaAccountDto account(String auth, String domain) {
        return new AnkamaAccountDto(ID, "Bot#0001", auth, "READY", 0, false, true, 3, domain);
    }

    @Test
    void startLoginRelaysTheAuthorizeUrl_forANewBotAndForAReauth() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminStartAnkamaLogin(null)).thenReturn(new AdminCall<>(200,
                new AnkamaLoginStartDto(LOGIN, "https://auth.ankama.com/authorize?x=1"), null));
        when(apiService.adminStartAnkamaLogin(ID)).thenReturn(new AdminCall<>(404, null, null));

        mvc.perform(admin(post(BASE + "/login"), "{}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value(LOGIN.toString()))
                .andExpect(jsonPath("$.authorizeUrl").value("https://auth.ankama.com/authorize?x=1"));
        mvc.perform(admin(post(BASE + "/login"), "{\"accountId\":\"" + ID + "\"}")).andExpect(status().isNotFound());
        verify(apiService).adminStartAnkamaLogin(ID);
    }

    @Test
    void completeLoginRelaysTheAccount_andTheApisRefusalMessage() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCompleteAnkamaLogin(LOGIN, "http://127.0.0.1:9001/authorized?code=a"))
                .thenReturn(new AdminCall<>(200, account("CODE_REQUIRED", "example.com"), null));
        when(apiService.adminCompleteAnkamaLogin(LOGIN, "bad"))
                .thenReturn(new AdminCall<>(400, null, "That address holds no code"));

        mvc.perform(admin(post(BASE + "/login/" + LOGIN + "/complete"),
                        "{\"redirectUrl\":\" http://127.0.0.1:9001/authorized?code=a \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authState").value("CODE_REQUIRED"))
                .andExpect(jsonPath("$.shieldCodeDomain").value("example.com"));
        mvc.perform(admin(post(BASE + "/login/" + LOGIN + "/complete"), "{\"redirectUrl\":\"bad\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("That address holds no code"));
        mvc.perform(admin(post(BASE + "/login/" + LOGIN + "/complete"), "{\"redirectUrl\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/login/" + LOGIN + "/complete"), "{}")).andExpect(status().isBadRequest());
        verify(apiService, org.mockito.Mockito.times(2)).adminCompleteAnkamaLogin(any(), any());
    }

    @Test
    void codeIsForwarded_withTheApisRefusalMessage_andResendRelaysTheStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminSubmitAnkamaCode(ID, "257146")).thenReturn(new AdminCall<>(202, null, null));
        when(apiService.adminSubmitAnkamaCode(ID, "1")).thenReturn(new AdminCall<>(400, null, "Wrong code"));
        when(apiService.adminResendAnkamaCode(ID)).thenReturn(502);

        mvc.perform(admin(post(BASE + "/" + ID + "/code"), "{\"code\":\" 257146\"}"))
                .andExpect(status().isAccepted()).andExpect(content().string(""));
        mvc.perform(admin(post(BASE + "/" + ID + "/code"), "{\"code\":\"1\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Wrong code"));
        mvc.perform(admin(post(BASE + "/" + ID + "/code"), "{\"code\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/" + ID + "/code/resend"), "{}")).andExpect(status().isBadGateway());
    }

    @Test
    void enabledResetLimitAndDetailsPatchTheAccount() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminPatchAnkamaAccount(any(), any())).thenReturn(new AdminCall<>(200, account("READY", null), null));
        when(apiService.adminPatchAnkamaAccount(ID, Map.of("friendLimitReached", false))).thenReturn(new AdminCall<>(404, null, null));

        mvc.perform(admin(post(BASE + "/" + ID + "/enabled"), "{\"enabled\":false}")).andExpect(status().isOk());
        verify(apiService).adminPatchAnkamaAccount(ID, Map.of("enabled", false));
        mvc.perform(admin(post(BASE + "/" + ID + "/enabled"), "{}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/" + ID + "/reset-limit"), "{}")).andExpect(status().isNotFound());
        mvc.perform(admin(post(BASE + "/" + ID + "/details"), "{\"fillOrder\":3}")).andExpect(status().isOk());
        verify(apiService).adminPatchAnkamaAccount(ID, Map.of("fillOrder", 3));
        mvc.perform(admin(post(BASE + "/" + ID + "/details"), "{\"fillOrder\":-1}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/" + ID + "/details"), "{}")).andExpect(status().isBadRequest());
    }

    @Test
    void deleteRelaysTheStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminDeleteAnkamaAccount(ID)).thenReturn(204);

        mvc.perform(admin(post(BASE + "/" + ID + "/delete"), "{}")).andExpect(status().isNoContent());
    }

    @Test
    void nonAdminIsRefusedBeforeTheApiIsCalled() throws Exception {
        when(apiService.isAdmin()).thenReturn(false);

        mvc.perform(admin(post(BASE + "/login"), "{}")).andExpect(status().is4xxClientError());
        mvc.perform(admin(post(BASE + "/login/" + LOGIN + "/complete"), "{\"redirectUrl\":\"x\"}")).andExpect(status().is4xxClientError());
        verify(apiService, never()).adminStartAnkamaLogin(any());
        verify(apiService, never()).adminCompleteAnkamaLogin(any(), any());
    }
}
