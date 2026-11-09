package fr.enimaloc.catapult.api.userapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class PublicBaseUrlTest {

    private final PublicBaseUrl publicBaseUrl = new PublicBaseUrl("https://configured.example/");

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void request(String scheme, int port, String contextPath) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme(scheme);
        request.setServerName("catapult.example");
        request.setServerPort(port);
        request.setContextPath(contextPath);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void outsideARequestIsTheConfiguredBaseUrl() {
        assertThat(publicBaseUrl.get()).isEqualTo("https://configured.example");
    }

    @Test
    void insideARequestIsTheRequestsOrigin() {
        request("https", 443, "");
        assertThat(publicBaseUrl.get()).isEqualTo("https://catapult.example");
    }

    @Test
    void aNonDefaultPortIsKept() {
        request("http", 8081, "");
        assertThat(publicBaseUrl.get()).isEqualTo("http://catapult.example:8081");
    }

    @Test
    void theContextPathIsKept() {
        request("https", 443, "/app");
        assertThat(publicBaseUrl.get()).isEqualTo("https://catapult.example/app");
    }
}
