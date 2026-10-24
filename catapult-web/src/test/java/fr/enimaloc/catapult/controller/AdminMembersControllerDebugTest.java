package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.debug.DebugMembers;
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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminMembersController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminMembersControllerDebugTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean DebugMembers debugMembers;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder debugPost(String action, boolean admin) {
        when(apiService.isAdmin()).thenReturn(admin);
        return post("/admin/members/" + ID + "/debug/" + action).with(csrf())
                .sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt");
    }

    @Test
    void enable_forwardsRevealSecrets() throws Exception {
        when(apiService.enableMemberDebug(ID, true)).thenReturn(204);
        when(apiService.enableMemberDebug(ID, false)).thenReturn(204);

        mvc.perform(debugPost("enable", true).contentType(MediaType.APPLICATION_JSON).content("{\"revealSecrets\":true}"))
                .andExpect(status().isNoContent());
        verify(apiService).enableMemberDebug(ID, true);

        mvc.perform(debugPost("enable", true).contentType(MediaType.APPLICATION_JSON).content("{\"revealSecrets\":false}"))
                .andExpect(status().isNoContent());
        verify(apiService).enableMemberDebug(ID, false);
    }

    @Test
    void anEmptyObjectOrNoBody_meansMasked() throws Exception {
        when(apiService.enableMemberDebug(ID, false)).thenReturn(204);

        mvc.perform(debugPost("enable", true).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent());
        mvc.perform(debugPost("enable", true)).andExpect(status().isNoContent());

        verify(apiService, org.mockito.Mockito.times(2)).enableMemberDebug(ID, false);
    }

    @Test
    void notFoundAndUnreachable_areRelayed() throws Exception {
        when(apiService.enableMemberDebug(ID, false)).thenReturn(404);
        when(apiService.disableMemberDebug(ID)).thenReturn(404);
        when(apiService.clearMemberDebug(ID)).thenReturn(502);

        mvc.perform(debugPost("enable", true).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(debugPost("disable", true)).andExpect(status().isNotFound());
        mvc.perform(debugPost("clear", true)).andExpect(status().isBadGateway());
        when(apiService.enableMemberDebug(ID, false)).thenReturn(502);
        when(apiService.disableMemberDebug(ID)).thenReturn(502);
        mvc.perform(debugPost("enable", true)).andExpect(status().isBadGateway());
        mvc.perform(debugPost("disable", true)).andExpect(status().isBadGateway());
        when(apiService.clearMemberDebug(ID)).thenReturn(404);
        mvc.perform(debugPost("clear", true)).andExpect(status().isNotFound());
    }

    @Test
    void disableAndClear_areForwarded_andTheMembersCachedStateForgotten() throws Exception {
        when(apiService.disableMemberDebug(ID)).thenReturn(204);
        when(apiService.clearMemberDebug(ID)).thenReturn(204);

        mvc.perform(debugPost("disable", true)).andExpect(status().isNoContent());
        mvc.perform(debugPost("clear", true)).andExpect(status().isNoContent());

        verify(apiService).disableMemberDebug(ID);
        verify(apiService).clearMemberDebug(ID);
        verify(debugMembers, org.mockito.Mockito.times(2)).evict(ID);
    }

    @Test
    void catapultApisRefusal_isAnswered() throws Exception {
        when(apiService.enableMemberDebug(ID, false)).thenReturn(409);

        mvc.perform(debugPost("enable", true).contentType(MediaType.APPLICATION_JSON).content("{\"revealSecrets\":false}"))
                .andExpect(status().isConflict());
    }

    @Test
    void aNonAdmin_isRefused_andNothingIsForwarded() throws Exception {
        mvc.perform(debugPost("enable", false).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(debugPost("disable", false)).andExpect(status().is4xxClientError());
        mvc.perform(debugPost("clear", false)).andExpect(status().is4xxClientError());

        org.mockito.Mockito.verify(apiService, org.mockito.Mockito.never()).enableMemberDebug(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
        verifyNoInteractions(debugMembers);
    }
}
