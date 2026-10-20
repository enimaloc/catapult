package fr.enimaloc.catapult.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class AccessLogFilterTest {
    private final AccessLogFilter filter = new AccessLogFilter(new MockEnvironment());

    private static AccessLogFilter devFilter() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        return new AccessLogFilter(env);
    }

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

    /** Static assets, the re-served external ones included, only at DEBUG (off by default). */
    @Test
    void keepsStaticAssetsOutOfTheInfoLog(CapturedOutput output) throws Exception {
        for (String uri : new String[]{"/css/app.css", "/js/spa.js", "/assets/ext/fonts/material-icons.css"}) {
            filter.doFilter(request("GET", uri), new MockHttpServletResponse(), new MockFilterChain());
            assertThat(output).doesNotContain(uri);
        }
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

    @Test
    void staysOnOneLineOutsideDev(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = request("GET", "/channels");
        request.addHeader("User-Agent", "test-agent");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(output).containsPattern("GET /channels 200 \\d+ms \\S+ \\(UA: test-agent\\)")
                .doesNotContain("> GET");
    }

    @Test
    void showsTheWholeExchangeLikeCurlVerbose_inDev(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = request("GET", "/auth/callback");
        request.setQueryString("code=1");
        request.addHeader("User-Agent", "test-agent");
        request.addHeader("Cookie", "SESSION=abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        devFilter().doFilter(request, response, (req, res) -> {
            HttpServletResponse r = (HttpServletResponse) res;
            r.setStatus(302);
            r.setHeader("Location", "/channels");
            r.addHeader("Set-Cookie", "SESSION=def");
        });

        assertThat(output)
                .contains("> GET /auth/callback?code=1 HTTP/1.1")
                .contains("> User-Agent: test-agent")
                .contains("> Cookie: ***")
                .contains("< HTTP/1.1 302")
                .contains("< Location: /channels")
                .contains("< Set-Cookie: ***")
                .doesNotContain("abc").doesNotContain("def");
    }

    @Test
    void masksTheWidgetToken(CapturedOutput output) throws Exception {
        String token = "11111111-2222-3333-4444-555555555555";
        filter.doFilter(request("GET", "/widget/obs/" + token + "/events"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(request("GET", "/widget/obs/" + token), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(output).containsPattern("GET /widget/obs/\\*\\*\\*/events 200 \\d+ms")
                .containsPattern("GET /widget/obs/\\*\\*\\* 200 \\d+ms")
                .doesNotContain(token);
    }

    @Test
    void masksTheWidgetToken_inTheDevExchangeToo(CapturedOutput output) throws Exception {
        String token = "11111111-2222-3333-4444-555555555555";
        MockHttpServletRequest request = request("GET", "/widget/twitchat/" + token);
        // The widget page's own scripts are asked with it as their referrer.
        request.addHeader("Referer", "https://catapult.test/widget/obs/" + token);

        devFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            HttpServletResponse r = (HttpServletResponse) res;
            r.setStatus(301);
            r.setHeader("Location", "/widget/obs/" + token);
        });

        assertThat(output)
                .contains("GET /widget/twitchat/*** 301")
                .contains("> GET /widget/twitchat/*** HTTP/1.1")
                .contains("> Referer: https://catapult.test/widget/obs/***")
                .contains("< Location: /widget/obs/***")
                .doesNotContain(token);
    }
}
