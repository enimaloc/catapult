package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.debug.DebugMembers;
import fr.enimaloc.catapult.service.dryrun.DryRunMembers;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin's switch for a member's dry run, relayed to catapult-api. */
@WebMvcTest(controllers = AdminMembersController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminMembersControllerDryRunTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean DebugMembers debugMembers;
    @MockitoBean DryRunMembers dryRunMembers;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder dryRunPost(String action, boolean admin) {
        when(apiService.isAdmin()).thenReturn(admin);
        return post("/admin/members/" + ID + "/dry-run/" + action).with(csrf())
                .sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt");
    }

    @Test
    void enableAndDisable_relayCatapultApisAnswer_andForgetTheMembersRememberedState() throws Exception {
        when(apiService.enableMemberDryRun(ID)).thenReturn(200);
        when(apiService.disableMemberDryRun(ID)).thenReturn(204);

        mvc.perform(dryRunPost("enable", true)).andExpect(status().isNoContent());
        mvc.perform(dryRunPost("disable", true)).andExpect(status().isNoContent());

        verify(apiService).enableMemberDryRun(ID);
        verify(apiService).disableMemberDryRun(ID);
        verify(dryRunMembers, org.mockito.Mockito.times(2)).evict(ID);
    }

    @Test
    void alreadyInDryRun_unknownAndUnreachable_areRelayed() throws Exception {
        when(apiService.enableMemberDryRun(ID)).thenReturn(409);
        when(apiService.disableMemberDryRun(ID)).thenReturn(404);

        mvc.perform(dryRunPost("enable", true)).andExpect(status().isConflict());
        mvc.perform(dryRunPost("disable", true)).andExpect(status().isNotFound());

        when(apiService.enableMemberDryRun(ID)).thenReturn(502);
        mvc.perform(dryRunPost("enable", true)).andExpect(status().isBadGateway());
    }

    @Test
    void aMember_isRefused_andNothingIsSent() throws Exception {
        mvc.perform(dryRunPost("enable", false)).andExpect(status().isForbidden());
        mvc.perform(dryRunPost("disable", false)).andExpect(status().isForbidden());

        verify(apiService, never()).enableMemberDryRun(ID);
        verify(apiService, never()).disableMemberDryRun(ID);
        verifyNoInteractions(dryRunMembers);
    }

    @Test
    void loggedOut_isRefused() throws Exception {
        mvc.perform(post("/admin/members/" + ID + "/dry-run/enable").with(csrf())).andExpect(status().isUnauthorized());

        verify(apiService, never()).enableMemberDryRun(ID);
    }
}
