package fr.enimaloc.catapult.simulation.panel;

import fr.enimaloc.catapult.simulation.account.MockRequests;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;
import java.util.Set;

/**
 * Who reaches the control panel: a loopback client ({@link MockRequests#clientLoopback}) that also addresses
 * catapult-api by a loopback name. The host check stops a page of another site whose name was rebound to
 * {@code 127.0.0.1} (DNS rebinding): its requests come from the loopback, but under its own host name.
 */
final class MockPanelAccess {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "::1", "[::1]", "0:0:0:0:0:0:0:1");

    private MockPanelAccess() {}

    static boolean allowed(HttpServletRequest request) {
        return MockRequests.clientLoopback(request) && loopbackHost(request.getServerName());
    }

    private static boolean loopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String name = host.toLowerCase(Locale.ROOT);
        return LOOPBACK_HOSTS.contains(name) || name.startsWith("127.") && name.chars()
                .allMatch(c -> c == '.' || Character.isDigit(c));
    }
}
