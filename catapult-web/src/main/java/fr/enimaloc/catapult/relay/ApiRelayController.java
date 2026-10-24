package fr.enimaloc.catapult.relay;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * The paths of catapult-api a browser reaches itself, relayed from catapult-web's own origin: its Twitch
 * login ({@code /oauth2/}, {@code /login/oauth2/}), Steam's return, the public game and user APIs, the custom
 * bindings API (a Stream Deck or a script, authenticated by its personal token alone), and Swagger. catapult-web is the site's only entry point: the login keeps its cookies and its callback
 * URLs on the one public host.
 *
 * <p>What goes through is the request as the visitor sent it (cookies and authorization included), minus
 * the hop-by-hop headers, plus the {@code X-Forwarded-*} catapult-api builds its public URLs from (its
 * Twitch and Steam return addresses): the scheme, host, port and client IP as catapult-web saw them,
 * through its own {@code server.forward-headers-strategy}. The answer comes back as is, redirects
 * included (never followed here). An unreachable catapult-api answers the maintenance page (503).
 *
 * <p>Not in the mock profile: there is no catapult-api, MockAuthController stands in for its login.
 */
@Slf4j
@Controller
@Profile("!mock")
public class ApiRelayController {
    /**
     * Every path relayed (WebSecurityConfig leaves them to catapult-api's own CSRF rules); {@link #relay}'s
     * mapping repeats them, an annotation taking no array constant.
     */
    public static final String[] PATHS = {
            "/oauth2/**", "/login/oauth2/**", "/api/connect/steam/callback", "/api/game/**", "/api/user/**",
            "/swagger-ui.html", "/swagger-ui/**", "/api/v3/api-docs", "/api/v3/api-docs/**",
            "/api/v2/custom-bindings", "/api/v2/custom-bindings/**",
    };
    /** A relayed request's body is read whole: these endpoints only take small ones. */
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    /** Hop-by-hop headers (RFC 9110 §7.6.1), and those the JDK client sets itself. */
    private static final Set<String> NOT_RELAYED = Set.of("connection", "keep-alive", "proxy-connection",
            "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade",
            "http2-settings", "host", "content-length", "expect");
    /** Replaced by the ones catapult-web vouches for. */
    private static final Set<String> FORWARDING = Set.of("forwarded", "x-forwarded-for", "x-forwarded-proto",
            "x-forwarded-host", "x-forwarded-port", "x-real-ip");

    private final HttpClient http;
    private final String apiUrl;
    private final byte[] maintenancePage;

    @Autowired
    public ApiRelayController(@Value("${catapult.backend-url}") String apiUrl) {
        this(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build(), apiUrl);
    }

    ApiRelayController(HttpClient http, String apiUrl) {
        this.http = http;
        this.apiUrl = apiUrl.replaceAll("/+$", "");
        this.maintenancePage = MaintenancePage.bytes();
    }

    @RequestMapping({
            "/oauth2/**", "/login/oauth2/**", "/api/connect/steam/callback", "/api/game/**", "/api/user/**",
            "/swagger-ui.html", "/swagger-ui/**", "/api/v3/api-docs", "/api/v3/api-docs/**",
            "/api/v2/custom-bindings", "/api/v2/custom-bindings/**",
    })
    public void relay(HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            response.sendError(HttpStatus.CONTENT_TOO_LARGE.value());
            return;
        }
        HttpResponse<InputStream> answer;
        try {
            answer = http.send(upstream(request, body), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            log.warn("catapult-api unreachable for {} {}: {}", request.getMethod(), request.getRequestURI(), e.toString());
            unavailable(response);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            unavailable(response);
            return;
        }
        response.setStatus(answer.statusCode());
        answer.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("content-length")) {
                values.stream().findFirst().ifPresent(length -> response.setContentLengthLong(Long.parseLong(length)));
            } else if (!NOT_RELAYED.contains(lower) && !lower.startsWith(":")) {
                values.forEach(value -> response.addHeader(name, value));
            }
        });
        try (InputStream in = answer.body()) {
            in.transferTo(response.getOutputStream());
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** The visitor's request, for catapult-api, as it reached catapult-web. */
    HttpRequest upstream(HttpServletRequest request, byte[] body) {
        String query = request.getQueryString();
        // The URI as sent (still encoded): Spring Security's firewall has already refused any "..".
        URI uri = URI.create(apiUrl + request.getRequestURI() + (query == null ? "" : "?" + query));
        HttpRequest.Builder upstream = HttpRequest.newBuilder(uri)
                .timeout(READ_TIMEOUT)
                .method(request.getMethod(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (NOT_RELAYED.contains(lower) || FORWARDING.contains(lower)) continue;
            for (String value : Collections.list(request.getHeaders(name))) upstream.header(name, value);
        }
        return upstream
                .header("X-Forwarded-For", request.getRemoteAddr())
                .header("X-Real-IP", request.getRemoteAddr())
                .header("X-Forwarded-Proto", request.getScheme())
                .header("X-Forwarded-Host", request.getServerName())
                .header("X-Forwarded-Port", String.valueOf(request.getServerPort()))
                .build();
    }

    private void unavailable(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader("Retry-After", "30");
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("text/html;charset=UTF-8");
        response.setContentLength(maintenancePage.length);
        response.getOutputStream().write(maintenancePage);
    }
}
