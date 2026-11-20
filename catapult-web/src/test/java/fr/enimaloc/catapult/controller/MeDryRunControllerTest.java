package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.dryrun.DryRunMembers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The member's own way out of dry run (the banner's "Turn off"). */
@WebMvcTest(controllers = MeDryRunController.class)
@Import(WebSecurityConfig.class)
class MeDryRunControllerTest {
    private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean DryRunMembers dryRunMembers;

    @Test
    void disable_relaysCatapultApisAnswer_andForgetsTheRememberedState() throws Exception {
        when(apiService.currentUserId()).thenReturn(ME);
        when(apiService.disableMyDryRun()).thenReturn(204);

        mvc.perform(post("/me/dry-run/disable").with(csrf())).andExpect(status().isNoContent());

        verify(apiService).disableMyDryRun();
        verify(dryRunMembers).evict(ME);
    }

    @Test
    void disable_relaysAFailure_andStillForgetsTheRememberedState() throws Exception {
        when(apiService.currentUserId()).thenReturn(ME);
        when(apiService.disableMyDryRun()).thenReturn(502);

        mvc.perform(post("/me/dry-run/disable").with(csrf())).andExpect(status().isBadGateway());

        verify(dryRunMembers).evict(ME);
    }

    @Test
    void disable_loggedOut_isRefusedWithoutCallingCatapultApi() throws Exception {
        mvc.perform(post("/me/dry-run/disable").with(csrf())).andExpect(status().isUnauthorized());

        verify(apiService, never()).disableMyDryRun();
        verifyNoInteractions(dryRunMembers);
    }

    @Test
    void disable_withoutTheCsrfToken_isRefused() throws Exception {
        when(apiService.currentUserId()).thenReturn(ME);

        mvc.perform(post("/me/dry-run/disable")).andExpect(status().isForbidden());

        verify(apiService, never()).disableMyDryRun();
    }
}
