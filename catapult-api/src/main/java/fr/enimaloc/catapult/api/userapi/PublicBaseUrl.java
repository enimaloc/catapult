package fr.enimaloc.catapult.api.userapi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The origin (scheme, host, non-default port, context path) the links of a response must point at: the one the
 * current request came through, so they work on every public host and behind catapult-web's relay.
 *
 * <p>Inside a request, Tomcat has already applied {@code X-Forwarded-Proto/Host/Port} to it, but only when the peer
 * matches {@code server.tomcat.remoteip.internal-proxies} (the trusted proxies, loopback by default). Any other
 * client gets the {@code Host} it connected with, never a forged forwarded origin. Outside a request, it is the
 * configured {@code app.base-url}.
 *
 * <p>The OAuth2 redirect URI and the Steam OpenID {@code return_to}/{@code realm} stay on {@code app.base-url} on
 * purpose: the identity providers need a fixed, registered address. Never ends with a {@code /}.
 */
@Component
public class PublicBaseUrl {

    private final String configured;

    public PublicBaseUrl(@Value("${app.base-url:http://localhost:8080}") String configured) {
        this.configured = stripTrailingSlash(configured);
    }

    public String get() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return stripTrailingSlash(
                    ServletUriComponentsBuilder.fromContextPath(attributes.getRequest()).build().toUriString());
        }
        return configured;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
