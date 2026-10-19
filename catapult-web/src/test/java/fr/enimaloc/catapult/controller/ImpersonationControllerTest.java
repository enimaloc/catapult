package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ImpersonationController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class ImpersonationControllerTest {
    private static final String BODY = "{\"username\":\"alice\"}";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean NavEventStreamController navEvents;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private static MockHttpSession session(String jwt) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, jwt);
        return session;
    }

    @Test
    void impersonate_keepsTheAdminJwt_andSwapsTheSession() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminImpersonate("alice")).thenReturn("target-token");
        MockHttpSession session = session("admin");
        session.setAttribute(SessionChannels.ATTRIBUTE, java.util.List.of());

        mvc.perform(post("/admin/impersonate").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent());

        assertThat(session.getAttribute(ImpersonationController.ORIGINAL_JWT_KEY)).isEqualTo("admin");
        assertThat(session.getAttribute(ApiClient.SESSION_JWT_KEY)).isEqualTo("target-token");
        // what catapult-web remembered for the admin is not the member's
        verify(navEvents).forget(session);
    }

    @Test
    void impersonate_refused_changesNothing() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminImpersonate("alice")).thenReturn(null);
        MockHttpSession session = session("admin");

        mvc.perform(post("/admin/impersonate").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        assertThat(session.getAttribute(ApiClient.SESSION_JWT_KEY)).isEqualTo("admin");
        assertThat(session.getAttribute(ImpersonationController.ORIGINAL_JWT_KEY)).isNull();
        verify(navEvents, never()).forget(any());
    }

    @Test
    void impersonate_withoutAUsername_isABadRequest() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(post("/admin/impersonate").session(session("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\" \"}"))
                .andExpect(status().isBadRequest());

        verify(apiService, never()).adminImpersonate(any());
    }

    @Test
    void impersonate_isRefusedToAVisitorAMember_andWithoutCsrf() throws Exception {
        mvc.perform(post("/admin/impersonate").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/impersonate").session(session("member")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        when(apiService.isAdmin()).thenReturn(true);
        MockHttpSession admin = session("admin");
        mvc.perform(post("/admin/impersonate").session(admin).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        verify(apiService, never()).adminImpersonate(any());
        assertThat(admin.getAttribute(ApiClient.SESSION_JWT_KEY)).isEqualTo("admin");
    }

    /** An admin already impersonating (the owner keeps ROLE_ADMIN) keeps their own JWT to come back to. */
    @Test
    void impersonate_again_keepsTheFirstAdminJwt() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminImpersonate("alice")).thenReturn("second-token");
        MockHttpSession session = session("first-token");
        session.setAttribute(ImpersonationController.ORIGINAL_JWT_KEY, "admin");

        mvc.perform(post("/admin/impersonate").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent());

        assertThat(session.getAttribute(ImpersonationController.ORIGINAL_JWT_KEY)).isEqualTo("admin");
        assertThat(session.getAttribute(ApiClient.SESSION_JWT_KEY)).isEqualTo("second-token");
    }

    @Test
    void exit_restoresTheAdmin() throws Exception {
        MockHttpSession session = session("target-token");
        session.setAttribute(ImpersonationController.ORIGINAL_JWT_KEY, "admin");

        mvc.perform(post("/admin/impersonate/exit").session(session).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin#members"));

        assertThat(session.getAttribute(ApiClient.SESSION_JWT_KEY)).isEqualTo("admin");
        assertThat(session.getAttribute(ImpersonationController.ORIGINAL_JWT_KEY)).isNull();
        verify(navEvents).forget(session);
    }

    /** Review Focus 3: a stale tab or a second click only goes back to the admin page, never logs out. */
    @Test
    void exit_withoutImpersonation_onlyRedirects() throws Exception {
        MockHttpSession session = session("admin");

        mvc.perform(post("/admin/impersonate/exit").session(session).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin#members"));

        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute(ApiClient.SESSION_JWT_KEY)).isEqualTo("admin");
        verify(navEvents, never()).forget(any());
    }

    @Test
    void exit_needsTheCsrfToken() throws Exception {
        MockHttpSession session = session("target-token");
        session.setAttribute(ImpersonationController.ORIGINAL_JWT_KEY, "admin");

        mvc.perform(post("/admin/impersonate/exit").session(session)).andExpect(status().isForbidden());

        assertThat(session.getAttribute(ApiClient.SESSION_JWT_KEY)).isEqualTo("target-token");
    }
}
