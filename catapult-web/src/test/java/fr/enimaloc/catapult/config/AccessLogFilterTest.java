package fr.enimaloc.catapult.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class AccessLogFilterTest {
    private final AccessLogFilter filter = new AccessLogFilter();

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("203.0.113.7");
        return request;
    }

    @Test
    void logsTheMethodPathStatusAndClientIp_withoutTheQueryString(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = request("GET", "/auth/callback");
        request.setQueryString("code=secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(302));

        assertThat(output).containsPattern("GET /auth/callback 302 \\d+ms 203\\.0\\.113\\.7");
        assertThat(output).doesNotContain("secret");
    }

    @Test
    void skipsHealthChecks(CapturedOutput output) throws Exception {
        filter.doFilter(request("GET", "/actuator/health"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(output).doesNotContain("/actuator/health");
    }

    @Test
    void logsAFailedRequestAsA500(CapturedOutput output) {
        assertThatThrownBy(() -> filter.doFilter(request("POST", "/me/bot"), new MockHttpServletResponse(),
                (req, res) -> { throw new ServletException("boom"); })).isInstanceOf(ServletException.class);

        assertThat(output).containsPattern("POST /me/bot 500 \\d+ms");
    }

    @Test
    void logsAStreamOnlyOnceItCloses(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = request("GET", "/me/twitchat/events");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> req.startAsync());
        assertThat(output).doesNotContain("/me/twitchat/events");

        request.getAsyncContext().complete();
        assertThat(output).containsPattern("GET /me/twitchat/events 200 \\d+ms");
    }
}
