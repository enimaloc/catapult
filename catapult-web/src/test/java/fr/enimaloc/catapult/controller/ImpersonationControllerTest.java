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
    @MockitoBean SseStreams sse;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    /** The session ids whose streams were closed: before the swap rotates them. */
    private final java.util.List<String> forgottenIds = new java.util.ArrayList<>();

    @org.junit.jupiter.api.BeforeEach
    void recordForgottenIds() {
        org.mockito.Mockito.doAnswer(call -> forgottenIds.add(call.getArgument(0)))
                .when(sse).closeSession(any());
    }

    private static MockHttpSession session(String jwt) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, jwt);
        return session;
    }

    @Test
    void impersonate_keepsTheAdminJwt_andSwapsTheSession() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminImpersonate("alice")).thenReturn(new ApiClient.Result<>(200, "target-token"));
        MockHttpSession session = session("admin");
        session.setAttribute(SessionChannels.ATTRIBUTE, "[]");
        String id = session.getId();

        mvc.perform(post("/admin/impersonate").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent());

        assertThat(session.getAttribute(ImpersonationController.ORIGINAL_JWT_KEY)).isEqualTo("admin");
        assertThat(session.getAttribute(ApiClient.SESSION_JWT_KEY)).isEqualTo("target-token");
        // what catapult-web remembered for the admin is not the member's, forgotten under the former id
        assertThat(session.getAttribute(SessionChannels.ATTRIBUTE)).isNull();
        assertThat(forgottenIds).containsExactly(id);
        assertThat(session.getId()).isNotEqualTo(id);
    }

    @Test
    void impersonate_refused_changesNothing() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminImpersonate("alice")).thenReturn(new ApiClient.Result<>(403, null));
        MockHttpSession session = session("admin");
        String id = session.getId();

        mvc.perform(post("/admin/impersonate").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        assertThat(session.getAttribute(ApiClient.SESSION_JWT_KEY)).isEqualTo("admin");
        assertThat(session.getAttribute(ImpersonationController.ORIGINAL_JWT_KEY)).isNull();
        assertThat(session.getId()).isEqualTo(id);
        verify(sse, never()).closeSession(any());
    }

    @Test
    void impersonate_unreachableOrUnknown_relaysTheStatus_changesNothing() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminImpersonate("alice")).thenReturn(new ApiClient.Result<>(502, null));
        when(apiService.adminImpersonate("ghost")).thenReturn(new ApiClient.Result<>(404, null));
        MockHttpSession session = session("admin");

        mvc.perform(post("/admin/impersonate").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadGateway());
        mvc.perform(post("/admin/impersonate").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ghost\"}"))
                .andExpect(status().isNotFound());

        assertThat(session.getAttribute(ApiClient.SESSION_JWT_KEY)).isEqualTo("admin");
        assertThat(session.getAttribute(ImpersonationController.ORIGINAL_JWT_KEY)).isNull();
        verify(sse, never()).closeSession(any());
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
        when(apiService.adminImpersonate("alice")).thenReturn(new ApiClient.Result<>(200, "second-token"));
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
        String id = session.getId();

        mvc.perform(post("/admin/impersonate/exit").session(session).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin#members"));

        assertThat(session.getAttribute(ApiClient.SESSION_JWT_KEY)).isEqualTo("admin");
        assertThat(session.getAttribute(ImpersonationController.ORIGINAL_JWT_KEY)).isNull();
        assertThat(session.getAttribute(SessionChannels.ATTRIBUTE)).isNull();
        assertThat(forgottenIds).containsExactly(id);
        assertThat(session.getId()).isNotEqualTo(id);
    }

    /** Review Focus 3: a stale tab or a second click only goes back to the admin page, never logs out. */
    @Test
    void exit_withoutImpersonation_onlyRedirects() throws Exception {
        MockHttpSession session = session("admin");
        String id = session.getId();

        mvc.perform(post("/admin/impersonate/exit").session(session).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin#members"));

        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getId()).isEqualTo(id);
        assertThat(session.getAttribute(ApiClient.SESSION_JWT_KEY)).isEqualTo("admin");
        verify(sse, never()).closeSession(any());
    }

    @Test
    void exit_withoutASession_onlyRedirects_creatingNone() throws Exception {
        var result = mvc.perform(post("/admin/impersonate/exit").with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin#members"))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        verify(sse, never()).closeSession(any());
    }

    @Test
    void exit_needsTheCsrfToken() throws Exception {
        MockHttpSession session = session("target-token");
        session.setAttribute(ImpersonationController.ORIGINAL_JWT_KEY, "admin");

        mvc.perform(post("/admin/impersonate/exit").session(session)).andExpect(status().isForbidden());

        assertThat(session.getAttribute(ApiClient.SESSION_JWT_KEY)).isEqualTo("target-token");
    }
}
