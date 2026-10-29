package fr.enimaloc.catapult.simulation.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.security.AuthCodeStore;
import fr.enimaloc.catapult.security.InviteCodeRelayFilter;
import fr.enimaloc.catapult.security.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code mock} profile's login, catapult-web untouched: the Twitch authorization catapult-web relays
 * ({@code /oauth2/authorization/twitch}) first shows a choice page ({@link ChoicePage}), either the real Twitch login
 * or one of the fictitious accounts. A fictitious account gets the one-time code {@code TwitchLoginSuccessHandler}
 * issues, and goes back to catapult-web's usual callback with catapult-web's state, which then exchanges the code
 * through {@code POST /api/internal/auth/exchange} as after a real login.
 * <p>
 * The login route sits under {@code /oauth2/}, one of the paths catapult-web relays (and leaves to catapult-api's own
 * CSRF rules), so the login takes the choice page's one-time nonce ({@link MockLoginNonces}) instead. Both answer a
 * 404 unless the client is on the loopback ({@link MockRequests#clientLoopback}). No {@code TwitchLoginEvent} is
 * published: it counts Twitch logins, and a fictitious one is not. Only with the simulation on.
 */
@Slf4j
@Controller
@Profile("mock")
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
@DependsOn("mockGuard")
public class MockLoginController {

    static final String LOGIN_PATH = "/oauth2/mock/login/";
    static final String NONCE_PARAM = "nonce";
    /** A fictitious session never outlives a working day, whatever {@code app.jwt.expiry-hours} says. */
    static final Duration TOKEN_VALIDITY = Duration.ofHours(12);

    private final FictitiousAccountService accounts;
    private final JwtService jwtService;
    private final AuthCodeStore codeStore;
    private final MockLoginNonces nonces;
    private final String webUrl;

    public MockLoginController(FictitiousAccountService accounts, JwtService jwtService, AuthCodeStore codeStore,
                               MockLoginNonces nonces, @Value("${app.web-url:}") String webUrl) {
        this.accounts = accounts;
        this.jwtService = jwtService;
        this.codeStore = codeStore;
        this.nonces = nonces;
        this.webUrl = webUrl;
    }

    /**
     * Signs the fictitious account in: a one-time code for its JWT ({@code ROLE_USER}, and {@code ROLE_ADMIN} for a
     * fictitious admin), then catapult-web's {@code /auth/callback} with the code and catapult-web's state, as
     * {@code TwitchLoginSuccessHandler} does. 404 for a remote client, a missing, used or expired nonce of the
     * choice page ({@link MockLoginNonces}), an unknown or a real account.
     */
    @PostMapping(LOGIN_PATH + "{accountId}")
    public void login(@PathVariable String accountId,
                      @RequestParam(name = NONCE_PARAM, required = false) String nonce,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!MockRequests.clientLoopback(request) || !nonces.consume(nonce)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        UserAccount account = parse(accountId)
                .flatMap(accounts::fictitious)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        List<String> roles = new ArrayList<>(List.of("ROLE_USER"));
        if (accounts.isAdmin(account.getId())) {
            roles.add("ROLE_ADMIN");
        }
        String code = codeStore.issue(jwtService.generateForUser(account, roles, TOKEN_VALIDITY));
        UriComponentsBuilder callback = UriComponentsBuilder.fromUriString(webUrl)
                .path("/auth/callback")
                .queryParam("code", code);
        InviteCodeRelayFilter.pendingWebState(request).ifPresent(state -> callback.queryParam("state", state));
        InviteCodeRelayFilter.clearPendingWebState(request, response);
        log.info("Fictitious login of {} ({}), roles {}", account.getId(), account.getTwitchUsername(), roles);
        response.sendRedirect(callback.encode().build().toUriString());
    }

    private static Optional<UUID> parse(String accountId) {
        try {
            return Optional.of(UUID.fromString(accountId));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Serves the choice page in place of the Twitch authorization redirect, unless {@code real=1} asks for the real
     * login. Runs right after {@link InviteCodeRelayFilter} (which keeps catapult-web's state and the invite code for
     * either way) and before Spring Security's OAuth2 redirect.
     */
    @Component
    @Profile("mock")
    @ConditionalOnBooleanProperty("catapult.simulation.enabled")
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public static class ChoicePage extends OncePerRequestFilter {

        static final String AUTHORIZE_PATH = "/oauth2/authorization/twitch";
        static final String REAL_PARAM = "real";
        /** The path within the application: no context path, no {@code ;jsessionid=} or other path parameter. */
        private static final UrlPathHelper PATHS = new UrlPathHelper();

        private final FictitiousAccountService accounts;
        private final MockLoginNonces nonces;

        public ChoicePage(FictitiousAccountService accounts, MockLoginNonces nonces) {
            this.accounts = accounts;
            this.nonces = nonces;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !"GET".equals(request.getMethod())
                    || !AUTHORIZE_PATH.equals(PATHS.getPathWithinApplication(request))
                    || "1".equals(request.getParameter(REAL_PARAM));
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!MockRequests.clientLoopback(request)) {
                response.sendError(HttpStatus.NOT_FOUND.value());
                return;
            }
            byte[] page = page(request).getBytes(StandardCharsets.UTF_8);
            response.setStatus(HttpStatus.OK.value());
            response.setContentType("text/html;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.setContentLength(page.length);
            response.getOutputStream().write(page);
        }

        private String page(HttpServletRequest request) {
            String query = request.getQueryString();
            String realLogin = AUTHORIZE_PATH + "?" + (query == null || query.isBlank() ? "" : query + "&")
                    + REAL_PARAM + "=1";
            String nonce = HtmlUtils.htmlEscape(nonces.issue());
            StringBuilder list = new StringBuilder();
            for (UserAccount account : accounts.fictitiousAccounts()) {
                String name = account.getTwitchUsername() == null ? account.getTwitchId() : account.getTwitchUsername();
                list.append("<li><form method=\"post\" action=\"")
                        .append(HtmlUtils.htmlEscape(LOGIN_PATH + account.getId()))
                        .append("\"><input type=\"hidden\" name=\"" + NONCE_PARAM + "\" value=\"")
                        .append(nonce)
                        .append("\"><button type=\"submit\">")
                        .append(HtmlUtils.htmlEscape(name))
                        .append(accounts.isAdmin(account.getId()) ? " <small>(admin)</small>" : "")
                        .append("</button></form></li>");
            }
            if (list.isEmpty()) {
                list.append("<li><em>No fictitious account yet: create one in the mock control panel.</em></li>");
            }
            return """
                    <!doctype html>
                    <html lang="en">
                    <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>Sign in (mock)</title>
                    <style>
                    body { font-family: system-ui, sans-serif; max-width: 28rem; margin: 3rem auto; padding: 0 1rem; }
                    ul { list-style: none; padding: 0; }
                    li { margin: .5rem 0; }
                    button, a.real { display: block; width: 100%%; padding: .6rem; font-size: 1rem; text-align: left;
                      box-sizing: border-box; }
                    a.real { background: #9146ff; color: #fff; text-decoration: none; border-radius: 4px; }
                    </style>
                    </head>
                    <body>
                    <h1>Sign in</h1>
                    <p>The mock profile is on: sign in with Twitch, or as one of the fictitious accounts.</p>
                    <p><a class="real" href="%s">Continue with Twitch</a></p>
                    <h2>Fictitious accounts</h2>
                    <ul>%s</ul>
                    </body>
                    </html>
                    """.formatted(HtmlUtils.htmlEscape(realLogin), list);
        }
    }
}
