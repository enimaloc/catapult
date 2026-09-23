package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.LinkStateResponse;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ChannelActionsController.class)
@Import(WebSecurityConfig.class)
class ChannelActionsControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;

    @Test
    void toggleBot_callsApiServiceAndReturns204() throws Exception {
        mvc.perform(post("/channel/enimaloc/settings/bot").with(csrf()))
                .andExpect(status().isNoContent());

        verify(apiService).toggleBot("enimaloc");
    }

    @Test
    void recheckGame_callsApiServiceAndReturns204() throws Exception {
        mvc.perform(post("/channel/enimaloc/game/recheck").with(csrf()))
                .andExpect(status().isNoContent());

        verify(apiService).recheckGame("enimaloc");
    }

    @Test
    void toggleBot_withoutCsrf_isRejected() throws Exception {
        mvc.perform(post("/channel/enimaloc/settings/bot"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cclToggle_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/bindings/abc-123/ccl-toggle").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNoContent());

        verify(apiService).cclToggle("enimaloc", "abc-123", true);
    }

    @Test
    void ignoredToggle_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/bindings/abc-123/ignored-toggle").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"ignored\":true}"))
                .andExpect(status().isNoContent());

        verify(apiService).ignoredToggle("enimaloc", "abc-123", true);
    }

    @Test
    void deleteBinding_callsApiService() throws Exception {
        mvc.perform(post("/channel/enimaloc/bindings/abc-123/delete").with(csrf()))
                .andExpect(status().isNoContent());

        verify(apiService).deleteBinding("enimaloc", "abc-123");
    }

    @Test
    void updateBinding_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/bindings/abc-123").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"twitchGameId\":\"509658\",\"twitchGameName\":\"Celeste\",\"ccls\":[\"violent-graphic\"]}"))
                .andExpect(status().isNoContent());

        verify(apiService).updateBinding("enimaloc", "abc-123", "509658", "Celeste",
                java.util.Set.of("violent-graphic"));
    }

    @Test
    void saveTws_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/bindings/abc-123/tws").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"tws\":[\"jumpscares\"]}"))
                .andExpect(status().isNoContent());

        verify(apiService).saveTws("abc-123", java.util.Set.of("jumpscares"));
    }

    @Test
    void resetTws_callsApiService() throws Exception {
        mvc.perform(post("/channel/enimaloc/bindings/abc-123/tws/reset").with(csrf()))
                .andExpect(status().isNoContent());

        verify(apiService).resetTws("abc-123");
    }

    @Test
    void toggleTwEnabled_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/bindings/abc-123/tw-enabled").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNoContent());

        verify(apiService).toggleTwEnabled("abc-123", true);
    }

    @Test
    void saveSteamToken_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/settings/steam-personal-token").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"secret-token\",\"shared\":true}"))
                .andExpect(status().isNoContent());

        verify(apiService).saveSteamToken("enimaloc", "secret-token", true);
    }

    @Test
    void steamTokenSharing_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/settings/steam-personal-token/sharing").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"shared\":false}"))
                .andExpect(status().isNoContent());

        verify(apiService).steamTokenSharing("enimaloc", false);
    }

    @Test
    void deleteSteamToken_callsApiService() throws Exception {
        mvc.perform(post("/channel/enimaloc/settings/steam-personal-token/delete").with(csrf()))
                .andExpect(status().isNoContent());

        verify(apiService).deleteSteamToken("enimaloc");
    }

    @Test
    void refreshSteamProfileCache_callsApiService() throws Exception {
        mvc.perform(post("/channel/enimaloc/steam/refresh-profile-cache").with(csrf()))
                .andExpect(status().isNoContent());

        verify(apiService).refreshSteamProfileCache("enimaloc");
    }

    @Test
    void minecraftStatus_returnsApiServiceResult() throws Exception {
        when(apiService.minecraftStatus("enimaloc"))
                .thenReturn(new LinkStateResponse("ACCEPTED", "Steve", "bot-account-1"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/channel/enimaloc/minecraft"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void minecraftEnroll_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/minecraft/enroll").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Steve\"}"))
                .andExpect(status().isNoContent());

        verify(apiService).minecraftEnroll("enimaloc", "Steve");
    }

    @Test
    void minecraftSync_callsApiService() throws Exception {
        mvc.perform(post("/channel/enimaloc/minecraft/sync").with(csrf()))
                .andExpect(status().isNoContent());

        verify(apiService).minecraftSync("enimaloc");
    }

    @Test
    void minecraftDisconnect_callsApiService() throws Exception {
        mvc.perform(post("/channel/enimaloc/minecraft/disconnect").with(csrf()))
                .andExpect(status().isNoContent());

        verify(apiService).minecraftDisconnect("enimaloc");
    }

    @Test
    void saveCclSettings_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/settings/ccl").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"cclEnabled\":true,\"blockedCcls\":[\"violent-graphic\"]}"))
                .andExpect(status().isNoContent());

        verify(apiService).saveCclSettings("enimaloc", true, java.util.Set.of("violent-graphic"));
    }

    @Test
    void saveTwSettings_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/settings/tws").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"blockedTws\":[\"jumpscares\"]}"))
                .andExpect(status().isNoContent());

        verify(apiService).saveTwSettings("enimaloc", true, java.util.Set.of("jumpscares"));
    }

    @Test
    void saveNoGameSettings_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/settings/no-game").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"twitchGameId\":\"509658\",\"twitchGameName\":\"Celeste\",\"ccls\":[\"violent-graphic\"],\"applyOnStreamStart\":true,\"applyOnNoGame\":false,\"applyOnStreamEnd\":true}"))
                .andExpect(status().isNoContent());

        verify(apiService).saveNoGameSettings("enimaloc", "509658", "Celeste",
                java.util.Set.of("violent-graphic"), true, false, true);
    }

    @Test
    void saveIncompleteFallbackSettings_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/settings/incomplete-fallback").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"twitchGameId\":\"509658\",\"twitchGameName\":\"Celeste\",\"ccls\":[\"violent-graphic\"]}"))
                .andExpect(status().isNoContent());

        verify(apiService).saveIncompleteFallbackSettings("enimaloc", "509658", "Celeste",
                java.util.Set.of("violent-graphic"));
    }
}
