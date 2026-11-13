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
import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminBattleNetController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminBattleNetControllerTest {
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final String BASE = "/admin/battlenet-accounts";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void createForwardsCredentialsAndReturns202() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateBattleNetAccount("Bot1", "bot@example.com", " s3cret ")).thenReturn(202);

        mvc.perform(admin(post(BASE), "{\"label\":\" Bot1 \",\"email\":\"bot@example.com \",\"password\":\" s3cret \"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
        verify(apiService).adminCreateBattleNetAccount("Bot1", "bot@example.com", " s3cret ");
    }

    @Test
    void createWithAMissingFieldIs400AndNeverCallsTheApi() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(admin(post(BASE), "{\"label\":\"Bot1\",\"email\":\"\",\"password\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE), "{\"label\":\"Bot1\",\"email\":\"a@b.c\"}")).andExpect(status().isBadRequest());
        verify(apiService, never()).adminCreateBattleNetAccount(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void codeIsForwarded() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminSubmitBattleNetCode(ID, "123456")).thenReturn(202);

        mvc.perform(admin(post(BASE + "/" + ID + "/code"), "{\"code\":\" 123456\"}")).andExpect(status().isAccepted());
        verify(apiService).adminSubmitBattleNetCode(ID, "123456");
        mvc.perform(admin(post(BASE + "/" + ID + "/code"), "{\"code\":\"\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void codeWithNoWaitingLoginRelays409() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminSubmitBattleNetCode(ID, "1")).thenReturn(409);
        when(apiService.adminReauthBattleNetAccount(ID)).thenReturn(409);

        mvc.perform(admin(post(BASE + "/" + ID + "/code"), "{\"code\":\"1\"}")).andExpect(status().isConflict());
        mvc.perform(admin(post(BASE + "/" + ID + "/reauth"), "{}")).andExpect(status().isConflict());
    }

    @Test
    void enabledResetLimitAndPasswordPatchTheAccount() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminPatchBattleNetAccount(ID, Map.of("enabled", false))).thenReturn(200);
        when(apiService.adminPatchBattleNetAccount(ID, Map.of("friendLimitReached", false))).thenReturn(404);
        when(apiService.adminPatchBattleNetAccount(ID, Map.of("password", "n3w"))).thenReturn(200);

        mvc.perform(admin(post(BASE + "/" + ID + "/enabled"), "{\"enabled\":false}")).andExpect(status().isOk());
        mvc.perform(admin(post(BASE + "/" + ID + "/enabled"), "{}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/" + ID + "/reset-limit"), "{}")).andExpect(status().isNotFound());
        mvc.perform(admin(post(BASE + "/" + ID + "/password"), "{\"password\":\"n3w\"}")).andExpect(status().isOk());
        mvc.perform(admin(post(BASE + "/" + ID + "/password"), "{\"password\":\" \"}")).andExpect(status().isBadRequest());
    }

    @Test
    void detailsForwardsLabelAndFillOrder_andValidates() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminPatchBattleNetAccount(ID, Map.of("label", "Bot2", "fillOrder", 3))).thenReturn(200);

        mvc.perform(admin(post(BASE + "/" + ID + "/details"), "{\"label\":\" Bot2 \",\"fillOrder\":3}")).andExpect(status().isOk());
        mvc.perform(admin(post(BASE + "/" + ID + "/details"), "{\"label\":\" \",\"fillOrder\":3}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/" + ID + "/details"), "{\"label\":\"a\",\"fillOrder\":-1}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/" + ID + "/details"), "{\"label\":\"a\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void deleteRelaysTheStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminDeleteBattleNetAccount(ID)).thenReturn(204);

        mvc.perform(admin(post(BASE + "/" + ID + "/delete"), "{}")).andExpect(status().isNoContent());
    }

    @Test
    void nonAdminIsRefusedBeforeTheApiIsCalled() throws Exception {
        when(apiService.isAdmin()).thenReturn(false);

        mvc.perform(admin(post(BASE), "{\"label\":\"a\",\"email\":\"b\",\"password\":\"c\"}")).andExpect(status().is4xxClientError());
        verify(apiService, never()).adminCreateBattleNetAccount(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
