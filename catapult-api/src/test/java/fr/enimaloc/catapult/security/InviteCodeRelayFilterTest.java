package fr.enimaloc.catapult.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** InviteCodeRelayFilter: capturing ?invite= before the OAuth redirect, and handing it to the callback. */
class InviteCodeRelayFilterTest {

    private final InviteCodeRelayFilter filter = new InviteCodeRelayFilter();

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private MockHttpServletResponse bind(MockHttpServletRequest request) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        return response;
    }

    @Test
    void storesTheNormalizedCodeInSessionAndCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/twitch");
        request.setParameter("invite", " abc123 ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(request.getSession().getAttribute(InviteCodeRelayFilter.SESSION_KEY)).isEqualTo("ABC123");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("catapult_invite=ABC123").contains("Max-Age=600").contains("HttpOnly").contains("SameSite=Lax");
        verify(chain).doFilter(request, response);
    }

    @Test
    void ignoresOtherPathsAndBlankCodes() throws Exception {
        MockHttpServletRequest elsewhere = new MockHttpServletRequest("GET", "/oauth2/authorization/steam");
        elsewhere.setParameter("invite", "ABC");
        MockHttpServletRequest blank = new MockHttpServletRequest("GET", "/oauth2/authorization/twitch");
        blank.setParameter("invite", " ");
        MockHttpServletRequest none = new MockHttpServletRequest("GET", "/oauth2/authorization/twitch");

        for (MockHttpServletRequest request : new MockHttpServletRequest[]{elsewhere, blank, none}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, mock(FilterChain.class));
            assertThat(request.getSession(false)).isNull();
            assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        }
    }

    @Test
    void pendingCode_prefersTheSession_thenTheCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(InviteCodeRelayFilter.SESSION_KEY, "FROMSESSION");
        request.setSession(session);
        request.setCookies(new Cookie("other", "x"), new Cookie(InviteCodeRelayFilter.COOKIE_NAME, "FROMCOOKIE"));
        bind(request);
        assertThat(InviteCodeRelayFilter.pendingCode()).contains("FROMSESSION");

        MockHttpServletRequest cookieOnly = new MockHttpServletRequest();
        cookieOnly.setCookies(new Cookie(InviteCodeRelayFilter.COOKIE_NAME, "FROMCOOKIE"));
        bind(cookieOnly);
        assertThat(InviteCodeRelayFilter.pendingCode()).contains("FROMCOOKIE");

        MockHttpServletRequest otherCookies = new MockHttpServletRequest();
        otherCookies.setCookies(new Cookie("other", "x"));
        bind(otherCookies);
        assertThat(InviteCodeRelayFilter.pendingCode()).isEmpty();

        bind(new MockHttpServletRequest());
        assertThat(InviteCodeRelayFilter.pendingCode()).isEmpty();
    }

    @Test
    void pendingCode_outsideARequest_isEmpty() {
        assertThat(InviteCodeRelayFilter.pendingCode()).isEmpty();
    }

    @Test
    void clearPendingCode_dropsTheSessionAttributeAndExpiresTheCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(InviteCodeRelayFilter.SESSION_KEY, "CODE");
        request.setSession(session);
        MockHttpServletResponse response = bind(request);

        InviteCodeRelayFilter.clearPendingCode();

        assertThat(session.getAttribute(InviteCodeRelayFilter.SESSION_KEY)).isNull();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("catapult_invite=").contains("Max-Age=0").contains("Secure");
    }

    @Test
    void clearPendingCode_withoutSession_onlyExpiresTheCookie_andOutsideARequestDoesNothing() {
        MockHttpServletResponse response = bind(new MockHttpServletRequest());
        InviteCodeRelayFilter.clearPendingCode();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");

        RequestContextHolder.resetRequestAttributes();
        InviteCodeRelayFilter.clearPendingCode();
    }
}
