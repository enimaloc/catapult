package fr.enimaloc.catapult.simulation.account;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class MockRequestsTest {

    private static MockHttpServletRequest from(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    void client_loopback_accepts_a_local_relay_of_a_local_client() {
        assertThat(MockRequests.clientLoopback(from("127.0.0.1", null))).isTrue();
        assertThat(MockRequests.clientLoopback(from("0:0:0:0:0:0:0:1", null))).isTrue();
        assertThat(MockRequests.clientLoopback(from("127.0.0.1", "127.0.0.1"))).isTrue();
        assertThat(MockRequests.clientLoopback(from("127.0.0.1", "::1, 127.0.0.1"))).isTrue();
    }

    @Test
    void client_loopback_refuses_any_remote_hop() {
        // A remote client relayed by catapult-web, as the RemoteIpValve resolves it
        assertThat(MockRequests.clientLoopback(from("203.0.113.7", null))).isFalse();
        assertThat(MockRequests.clientLoopback(from("192.168.1.5", null))).isFalse();
        assertThat(MockRequests.clientLoopback(from("127.0.0.1", "203.0.113.7"))).isFalse();
        assertThat(MockRequests.clientLoopback(from("127.0.0.1", "203.0.113.7, 127.0.0.1"))).isFalse();
        assertThat(MockRequests.clientLoopback(from("127.0.0.1", "localhost"))).isFalse();
        assertThat(MockRequests.clientLoopback(from("127.0.0.1", "unknown"))).isFalse();
    }
}
