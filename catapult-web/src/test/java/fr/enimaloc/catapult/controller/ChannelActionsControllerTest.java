package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import fr.enimaloc.catapult.event.binding.GameChangedEvent;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ChannelActionsController.class)
@Import(WebSecurityConfig.class)
@RecordApplicationEvents
class ChannelActionsControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ApplicationEvents events;
    @MockitoBean ApiService apiService;

    @org.junit.jupiter.api.BeforeEach
    void catapultApiTakesTheActions() {
        ApiServiceStubs.actionsAnswer(apiService, 204);
    }

    @Test
    void anAction_answersCatapultApisRefusalOrFailure() throws Exception {
        when(apiService.cclToggle("enimaloc", "b1", true)).thenReturn(403);
        mvc.perform(post("/channel/enimaloc/bindings/b1/ccl-toggle").with(csrf())
                        .contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());

        when(apiService.toggleBot("enimaloc")).thenReturn(502);
        mvc.perform(post("/channel/enimaloc/settings/bot").with(csrf())).andExpect(status().isBadGateway());

        when(apiService.toggleBot("enimaloc")).thenReturn(200);
        mvc.perform(post("/channel/enimaloc/settings/bot").with(csrf())).andExpect(status().isNoContent());
    }

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
    void recheckGame_publishesNothingItself() throws Exception {
        mvc.perform(post("/channel/enimaloc/game/recheck").with(csrf()));

        assertThat(events.stream(GameChangedEvent.class)).isEmpty();
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
        mvc.perform(post("/channel/bindings/abc-123/tws").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"tws\":[\"jumpscares\"]}"))
                .andExpect(status().isNoContent());

        verify(apiService).saveTws("abc-123", java.util.Set.of("jumpscares"));
    }

    @Test
    void resetTws_callsApiService() throws Exception {
        mvc.perform(post("/channel/bindings/abc-123/tws/reset").with(csrf()))
                .andExpect(status().isNoContent());

        verify(apiService).resetTws("abc-123");
    }

    @Test
    void toggleTwEnabled_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/bindings/abc-123/tw-enabled").with(csrf())
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

    /** A session logged in as {@code login}, its JWT naming it (unsigned: catapult-web only reads it). */
    private static org.springframework.mock.web.MockHttpSession as(String login) {
        java.util.Base64.Encoder base64 = java.util.Base64.getUrlEncoder().withoutPadding();
        String payload = base64.encodeToString(("{\"username\":\"" + login + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(fr.enimaloc.catapult.service.http.ApiClient.SESSION_JWT_KEY, "e30." + payload + ".sig");
        return session;
    }

    @Test
    void minecraftStatus_returnsApiServiceResult() throws Exception {
        when(apiService.minecraftStatus("enimaloc"))
                .thenReturn(new LinkStateResponse("ACCEPTED", "Steve", "bot-account-1"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/channel/enimaloc/minecraft")
                        .session(as("Enimaloc")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void minecraftEnroll_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/minecraft/enroll").with(csrf()).session(as("enimaloc"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" Steve \"}"))
                .andExpect(status().isNoContent());

        verify(apiService).minecraftEnroll("enimaloc", "Steve");
    }

    @Test
    void minecraftEnroll_withoutAName_isABadRequest_notSentOn() throws Exception {
        for (String body : new String[]{"{}", "{\"name\":\"  \"}"}) {
            mvc.perform(post("/channel/enimaloc/minecraft/enroll").with(csrf()).session(as("enimaloc"))
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        org.mockito.Mockito.verify(apiService, org.mockito.Mockito.never()).minecraftEnroll(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** The link is the member's own: never reached through another channel, nor logged out. */
    @Test
    void minecraft_onAnotherChannel_isForbidden() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/channel/someone/minecraft")
                .session(as("enimaloc"))).andExpect(status().isForbidden());
        mvc.perform(post("/channel/someone/minecraft/enroll").with(csrf()).session(as("enimaloc"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"name\":\"Steve\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/channel/someone/minecraft/sync").with(csrf()).session(as("enimaloc"))).andExpect(status().isForbidden());
        mvc.perform(post("/channel/enimaloc/minecraft/disconnect").with(csrf())).andExpect(status().isForbidden());

        org.mockito.Mockito.verify(apiService, org.mockito.Mockito.never()).minecraftStatus(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(apiService, org.mockito.Mockito.never()).minecraftEnroll(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(apiService, org.mockito.Mockito.never()).minecraftSync(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(apiService, org.mockito.Mockito.never()).minecraftDisconnect(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void minecraftSync_callsApiService() throws Exception {
        mvc.perform(post("/channel/enimaloc/minecraft/sync").with(csrf()).session(as("enimaloc")))
                .andExpect(status().isNoContent());

        verify(apiService).minecraftSync("enimaloc");
    }

    @Test
    void minecraftDisconnect_callsApiService() throws Exception {
        mvc.perform(post("/channel/enimaloc/minecraft/disconnect").with(csrf()).session(as("enimaloc")))
                .andExpect(status().isNoContent());

        verify(apiService).minecraftDisconnect("enimaloc");
    }

    @Test
    void xboxDisconnect_callsApiService() throws Exception {
        mvc.perform(post("/channel/enimaloc/xbox/disconnect").with(csrf()).session(as("enimaloc")))
                .andExpect(status().isNoContent());

        verify(apiService).xboxDisconnect("enimaloc");
    }

    @Test
    void xboxDisconnect_onAnotherChannel_isForbidden() throws Exception {
        mvc.perform(post("/channel/someone/xbox/disconnect").with(csrf()).session(as("enimaloc")))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(apiService, org.mockito.Mockito.never()).xboxDisconnect(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void saveObsSettings_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/settings/obs").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"host\":\"10.0.0.2\",\"port\":4456,\"password\":null}"))
                .andExpect(status().isNoContent());

        verify(apiService).saveObsSettings("enimaloc", true, "10.0.0.2", 4456, null);
    }

    @Test
    void saveTwitchatBranch_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/enimaloc/settings/twitchat/branch").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"branch\":\"beta\"}"))
                .andExpect(status().isNoContent());

        verify(apiService).saveTwitchatBranch("enimaloc", "beta");
    }

    @Test
    void regenerateTwitchatWidget_relaysAndAnswersNoContent() throws Exception {
        when(apiService.regenerateTwitchatWidget("enimaloc")).thenReturn(new fr.enimaloc.catapult.service.http.ApiClient.Result<>(200,
                new fr.enimaloc.catapult.common.dto.twitchat.TwitchatSettingsResponse(true, "h", 4455, true, "11111111-2222-3333-4444-555555555555")));

        mvc.perform(post("/channel/enimaloc/settings/twitchat/regenerate").with(csrf()))
                .andExpect(status().isNoContent());

        verify(apiService).regenerateTwitchatWidget("enimaloc");
    }

    @Test
    void regenerateTwitchatWidget_relaysCatapultApisStatus() throws Exception {
        when(apiService.regenerateTwitchatWidget("enimaloc")).thenReturn(new fr.enimaloc.catapult.service.http.ApiClient.Result<>(403, null));
        mvc.perform(post("/channel/enimaloc/settings/twitchat/regenerate").with(csrf()))
                .andExpect(status().isForbidden());

        when(apiService.regenerateTwitchatWidget("enimaloc")).thenReturn(new fr.enimaloc.catapult.service.http.ApiClient.Result<>(502, null));
        mvc.perform(post("/channel/enimaloc/settings/twitchat/regenerate").with(csrf()))
                .andExpect(status().isBadGateway());
    }

    @Test
    void regenerateTwitchatWidget_withoutCsrf_isRejectedAndNotRelayed() throws Exception {
        mvc.perform(post("/channel/enimaloc/settings/twitchat/regenerate"))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verifyNoInteractions(apiService);
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

    @Test
    void dtddSearch_returnsApiServiceResult() throws Exception {
        when(apiService.dtddSearch("celeste")).thenReturn(new fr.enimaloc.catapult.common.dto.channel.SearchResponse(
                List.of(new fr.enimaloc.catapult.common.dto.channel.SearchResultDto(4521L, "Celeste", "Video Game", null))));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/channel/dtdd-mapping/search").param("q", "celeste"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.results[0].name").value("Celeste"));
    }

    @Test
    void dtddValidate_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/dtdd-mapping/validate").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"igdbId\":\"100\"}"))
                .andExpect(status().isNoContent());

        verify(apiService).dtddValidate("100");
    }

    @Test
    void dtddPropose_callsApiServiceWithParsedBody() throws Exception {
        mvc.perform(post("/channel/dtdd-mapping/propose").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"igdbId\":\"100\",\"dtddId\":4521,\"reason\":\"wrong game\"}"))
                .andExpect(status().isNoContent());

        verify(apiService).dtddPropose("100", 4521L, "wrong game");
    }

    @Test
    void deleteAccount_relaysTheStatus_andNeedsCsrf() throws Exception {
        when(apiService.deleteAccount("me", "Me")).thenReturn(204);

        mvc.perform(post("/channel/me/settings/delete-account").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"confirmUsername\":\"Me\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/channel/me/settings/delete-account")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"confirmUsername\":\"Me\"}"))
                .andExpect(status().isForbidden());

        verify(apiService, org.mockito.Mockito.times(1)).deleteAccount("me", "Me");
    }

    @Test
    void deleteAccount_relaysARefusal() throws Exception {
        when(apiService.deleteAccount("other", "x")).thenReturn(403);

        mvc.perform(post("/channel/other/settings/delete-account").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"confirmUsername\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancelDeletion_relaysTheStatus_andNeedsCsrf() throws Exception {
        when(apiService.cancelAccountDeletion("me")).thenReturn(204);

        mvc.perform(post("/channel/me/settings/cancel-deletion").with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/channel/me/settings/cancel-deletion"))
                .andExpect(status().isForbidden());

        verify(apiService, org.mockito.Mockito.times(1)).cancelAccountDeletion("me");
    }
}
