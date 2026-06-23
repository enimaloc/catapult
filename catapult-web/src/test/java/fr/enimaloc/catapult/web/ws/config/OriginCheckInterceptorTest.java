package fr.enimaloc.catapult.web.ws.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OriginCheckInterceptorTest {

    private static ServerHttpRequest requestWithOrigin(String origin) {
        ServerHttpRequest req = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        if (origin != null) headers.set("Origin", origin);
        when(req.getHeaders()).thenReturn(headers);
        return req;
    }

    @Test
    void wildcard_pattern_accepts_everything() throws Exception {
        var interceptor = new OriginCheckInterceptor(new String[]{"*"});
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        boolean ok = interceptor.beforeHandshake(
                requestWithOrigin("https://evil.example.com"), response, null, Map.of());
        assertThat(ok).isTrue();
    }

    @Test
    void exact_match_passes() {
        var interceptor = new OriginCheckInterceptor(new String[]{"https://catapult.example.com"});
        assertThat(interceptor.matches("https://catapult.example.com")).isTrue();
    }

    @Test
    void glob_pattern_passes() {
        var interceptor = new OriginCheckInterceptor(new String[]{"https://*.catapult.example.com"});
        assertThat(interceptor.matches("https://app.catapult.example.com")).isTrue();
    }

    @Test
    void unlisted_origin_is_rejected_with_403() throws Exception {
        var interceptor = new OriginCheckInterceptor(new String[]{"https://catapult.example.com"});
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean ok = interceptor.beforeHandshake(
                requestWithOrigin("https://evil.example.com"), response, null, Map.of());

        assertThat(ok).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    void missing_origin_passes_for_same_origin_or_non_browser_clients() throws Exception {
        var interceptor = new OriginCheckInterceptor(new String[]{"https://catapult.example.com"});
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        boolean ok = interceptor.beforeHandshake(
                requestWithOrigin(null), response, null, Map.of());
        assertThat(ok).isTrue();
    }
}
