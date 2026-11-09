package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.MutableClock;
import fr.enimaloc.catapult.controller.SseStreams;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionJwtFilterTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final ApiService api = mock(ApiService.class);
    private final SseStreams sse = mock(SseStreams.class);
    private final SessionJwtFilter filter = new SessionJwtFilter(() -> api, () -> sse, clock);

    private static String jwt(Instant expiresAt) {
        String payload = "{\"sub\":\"x\",\"exp\":" + expiresAt.getEpochSecond() + "}";
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    private MockHttpSession session(String jwt) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, jwt);
        return session;
    }

    private void request(MockHttpSession session, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setSession(session);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    @Test
    void anExpiredJwt_logsTheSessionOut_withoutAskingCatapultApi() throws Exception {
        MockHttpSession session = session(jwt(NOW.minusSeconds(1)));
        String id = session.getId();

        request(session, "/channel/x");

        assertThat(session.isInvalid()).isTrue();
        verify(sse).closeSession(id);
        verify(api, never()).validateSession();
    }

    @Test
    void aJwtCatapultApiRefuses_logsTheSessionOut() throws Exception {
        when(api.validateSession()).thenReturn(401);
        MockHttpSession session = session(jwt(NOW.plusSeconds(3600)));

        request(session, "/");

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void anAcceptedJwt_isAskedAgainOnlyAfterAWhile() throws Exception {
        when(api.validateSession()).thenReturn(200);
        MockHttpSession session = session(jwt(NOW.plusSeconds(3600)));

        request(session, "/");
        clock.now = NOW.plus(SessionJwtFilter.REVALIDATE).minusSeconds(1);
        request(session, "/");
        verify(api, times(1)).validateSession();

        clock.now = NOW.plus(SessionJwtFilter.REVALIDATE);
        request(session, "/");
        verify(api, times(2)).validateSession();
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void catapultApiUnreachable_logsNoOneOut_andAsksAgainNextTime() throws Exception {
        when(api.validateSession()).thenReturn(502);
        MockHttpSession session = session(jwt(NOW.plusSeconds(3600)));

        request(session, "/");
        request(session, "/");

        assertThat(session.isInvalid()).isFalse();
        verify(api, times(2)).validateSession();
    }

    @Test
    void staticFiles_andVisitors_areNeverChecked() throws Exception {
        MockHttpSession session = session(jwt(NOW.minusSeconds(1)));

        request(session, "/js/spa.js");
        request(null, "/");

        assertThat(session.isInvalid()).isFalse();
        verify(api, never()).validateSession();
    }

    @Test
    void aTokenThatIsNoJwt_onlyExpiresWhenCatapultApiSaysSo() throws Exception {
        when(api.validateSession()).thenReturn(200);
        MockHttpSession session = session("mock-token");

        request(session, "/");

        assertThat(session.isInvalid()).isFalse();
    }
}
