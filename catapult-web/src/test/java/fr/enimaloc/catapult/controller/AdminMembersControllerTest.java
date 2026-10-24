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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminMembersController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminMembersControllerTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID GROUP = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;
    @MockitoBean fr.enimaloc.catapult.service.debug.DebugMembers debugMembers;

    @org.junit.jupiter.api.BeforeEach
    void catapultApiTakesTheActions() {
        ApiServiceStubs.actionsAnswer(apiService, 204);
    }

    private MockHttpServletRequestBuilder asAdmin(String action) {
        when(apiService.isAdmin()).thenReturn(true);
        return asMember(action);
    }

    private static MockHttpServletRequestBuilder asMember(String action) {
        return post("/admin/members/" + ID + "/" + action).with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt");
    }

    @Test
    void anActionCatapultApiRefuses_answersItsStatus() throws Exception {
        when(apiService.adminDeleteMember(ID)).thenReturn(409);
        when(apiService.adminToggleMemberBot(ID)).thenReturn(502);

        mvc.perform(asAdmin("delete")).andExpect(status().isConflict());
        mvc.perform(asAdmin("bot/toggle")).andExpect(status().isBadGateway());
    }

    @Test
    void eachAction_isForwardedForAnAdmin() throws Exception {
        mvc.perform(asAdmin("bot/toggle")).andExpect(status().isNoContent());
        mvc.perform(asAdmin("steam/unlink")).andExpect(status().isNoContent());
        mvc.perform(asAdmin("twitch/unlink")).andExpect(status().isNoContent());
        mvc.perform(asAdmin("promote-to-system")).andExpect(status().isNoContent());
        mvc.perform(asAdmin("delete")).andExpect(status().isNoContent());

        verify(apiService).adminToggleMemberBot(ID);
        verify(apiService).adminUnlinkMemberSteam(ID);
        verify(apiService).adminUnlinkMemberTwitch(ID);
        verify(apiService).adminPromoteMemberToSystem(ID);
        verify(apiService).adminDeleteMember(ID);
    }

    @Test
    void migrate_forwardsTheChosenParts() throws Exception {
        mvc.perform(asAdmin("migrate").contentType(MediaType.APPLICATION_JSON).content("""
                        {"targetId":"%s","migrateSettings":true,"migrateGetters":false,"migrateBindings":true}""".formatted(TARGET)))
                .andExpect(status().isNoContent());

        verify(apiService).adminMigrateMember(ID, TARGET, true, false, true);
    }

    @Test
    void migrate_withNothingToMigrateOrOntoItself_isABadRequest() throws Exception {
        mvc.perform(asAdmin("migrate").contentType(MediaType.APPLICATION_JSON).content("""
                        {"targetId":"%s","migrateSettings":false,"migrateGetters":false,"migrateBindings":false}""".formatted(TARGET)))
                .andExpect(status().isBadRequest());
        mvc.perform(asAdmin("migrate").contentType(MediaType.APPLICATION_JSON).content("""
                        {"targetId":"%s","migrateSettings":true,"migrateGetters":false,"migrateBindings":false}""".formatted(ID)))
                .andExpect(status().isBadRequest());

        verify(apiService, never()).adminMigrateMember(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void targeting_actionsRelayTheirStatus() throws Exception {
        when(apiService.adminSetMemberFlag(ID, "tier", "2")).thenReturn(204);
        when(apiService.adminDeleteMemberFlag(ID, "a/b %é")).thenReturn(204);
        when(apiService.adminAddMemberToGroup(ID, "staff")).thenReturn(404);
        when(apiService.adminRemoveMemberFromGroup(ID, GROUP)).thenReturn(502);

        mvc.perform(asAdmin("flags").contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"tier\",\"value\":\"2\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(asAdmin("flags/delete").contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"a/b %é\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(asAdmin("groups").contentType(MediaType.APPLICATION_JSON).content("{\"groupKey\":\"staff\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(asAdmin("groups/" + GROUP + "/delete").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void targeting_withoutAKeyOrGroup_isABadRequest_withoutCallingTheApi() throws Exception {
        mvc.perform(asAdmin("flags").contentType(MediaType.APPLICATION_JSON).content("{\"key\":\" \",\"value\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(asAdmin("flags/delete").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(asAdmin("groups").contentType(MediaType.APPLICATION_JSON).content("{\"groupKey\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(apiService, never()).adminSetMemberFlag(any(), any(), any());
        verify(apiService, never()).adminDeleteMemberFlag(any(), any());
        verify(apiService, never()).adminAddMemberToGroup(any(), any());
    }

    @Test
    void targeting_actionsAreRefusedToMembersAndVisitors_andNeedCsrf() throws Exception {
        for (String action : new String[]{"flags", "flags/delete", "groups", "groups/" + GROUP + "/delete"}) {
            mvc.perform(post("/admin/members/" + ID + "/" + action).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(asMember(action).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        }
        when(apiService.isAdmin()).thenReturn(true);
        mvc.perform(post("/admin/members/" + ID + "/flags").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"k\"}")).andExpect(status().isForbidden());

        verify(apiService, never()).adminSetMemberFlag(any(), any(), any());
        verify(apiService, never()).adminDeleteMemberFlag(any(), any());
        verify(apiService, never()).adminAddMemberToGroup(any(), any());
        verify(apiService, never()).adminRemoveMemberFromGroup(any(), any());
    }

    @Test
    void actions_areRefused_401LoggedOut_403ToAMember() throws Exception {
        mvc.perform(post("/admin/members/" + ID + "/delete").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(asMember("delete")).andExpect(status().isForbidden());

        verify(apiService, never()).adminDeleteMember(any());
    }

    @Test
    void actions_withoutCsrf_areRejected() throws Exception {
        mvc.perform(post("/admin/members/" + ID + "/delete").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(apiService);
    }
}
