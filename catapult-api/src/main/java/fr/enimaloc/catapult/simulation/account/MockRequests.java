package fr.enimaloc.catapult.simulation.account;

import fr.enimaloc.catapult.security.Authz;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;
import java.util.Set;

/**
 * Who may reach the {@code mock} profile's routes, the panel and the fictitious login alike: a loopback client,
 * relayed or not ({@link #clientLoopback}).
 * <p>
 * There is no "direct access only" check: with {@code server.forward-headers-strategy=native}, Tomcat's
 * {@code RemoteIpValve} replaces the remote address of a request relayed by a trusted proxy
 * ({@code server.tomcat.remoteip.internal-proxies}) with the client's from {@code X-Forwarded-For}, and drops the
 * entries it consumed from that header, so a request relayed by a local catapult-web cannot be told from a direct
 * one. What counts is the client: a remote client relayed by catapult-web resolves to its own address and is
 * refused, a local one is let through.
 */
public final class MockRequests {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    /** The loopback addresses {@link Authz#isLoopback} accepts, as they are written in {@code X-Forwarded-For}. */
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "[::1]");

    private MockRequests() {}

    /**
     * A loopback client, relayed or not: the resolved address is loopback, and so is
     * every address still listed in {@code X-Forwarded-For} (none once the valve has consumed them; all of them when
     * no valve resolved the request), so a hop from another machine is refused either way.
     */
    public static boolean clientLoopback(HttpServletRequest request) {
        if (!Authz.isLoopback(request)) {
            return false;
        }
        String forwarded = request.getHeader(FORWARDED_FOR);
        return forwarded == null || Arrays.stream(forwarded.split(","))
                .map(String::trim)
                .allMatch(LOOPBACK::contains);
    }
}
