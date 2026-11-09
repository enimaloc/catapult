package fr.enimaloc.catapult.config;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class StaticCacheFilterTest {

    private static MockHttpServletResponse serve(String path, MockFilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new StaticCacheFilter().doFilter(new MockHttpServletRequest("GET", path), response, chain);
        return response;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/js/spa-0123456789abcdef0123456789abcdef.js", "/css/catapult-0123456789abcdef0123456789abcdef.css",
            "/_static/mdui/2.1.4/mdui.global.js"})
    void aVersionedFile_isKeptAYear(String path) throws Exception {
        assertThat(serve(path, new MockFilterChain()).getHeader("Cache-Control")).isEqualTo(StaticCacheFilter.IMMUTABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/js/spa.js", "/img/favicon.svg", "/js/vendor/reporting-11.7.0.min.js"})
    void anUnversionedFile_isRevalidated(String path) throws Exception {
        assertThat(serve(path, new MockFilterChain()).getHeader("Cache-Control")).isEqualTo(StaticCacheFilter.REVALIDATE);
    }

    @Test
    void aPage_isLeftToTheSecurityChain() throws Exception {
        assertThat(serve("/channel/x", new MockFilterChain()).getHeader("Cache-Control")).isNull();
    }

    @Test
    void aMissingVersion_isNeverKept() throws Exception {
        MockFilterChain missing = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) throws IOException {
                ((HttpServletResponse) res).sendError(404);
            }
        };

        MockHttpServletResponse response = serve("/js/spa-0123456789abcdef0123456789abcdef.js", missing);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
}
