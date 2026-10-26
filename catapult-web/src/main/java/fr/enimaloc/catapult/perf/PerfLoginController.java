package fr.enimaloc.catapult.perf;

import fr.enimaloc.catapult.common.dto.TokenResponse;
import fr.enimaloc.catapult.security.SessionLogin;
import fr.enimaloc.catapult.service.http.ApiClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The {@code perf} profile's test login, for the browser and load runs of a throwaway stack: catapult-api's perf
 * login hands out a seeded account's JWT, and the session opens as the Twitch callback opens it
 * ({@link SessionLogin}). The member lands on their channel, the admin on the admin page. Open to anyone
 * (the default security chain lets every request through); absent outside {@code perf}.
 */
@Controller
@Profile("perf")
public class PerfLoginController {
    static final String API_LOGIN = "/api/internal/perf/login?username={username}";

    private final ApiClient api;
    private final SessionLogin sessionLogin;

    public PerfLoginController(ApiClient api, SessionLogin sessionLogin) {
        this.api = api;
        this.sessionLogin = sessionLogin;
    }

    /** Logs the session in as {@code username}; 404 for an account catapult-api does not know, 502 without a token. */
    @GetMapping("/perf/login")
    public String login(@RequestParam String username, HttpServletRequest request) {
        ApiClient.Result<TokenResponse> answer = api.callAs(HttpMethod.POST, API_LOGIN, null, TokenResponse.class,
                username);
        if (answer.status() == HttpStatus.NOT_FOUND.value()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown perf account");
        }
        if (!answer.ok() || answer.body() == null || answer.body().token() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No perf login from catapult-api");
        }
        sessionLogin.establish(request, answer.body().token());
        if (ApiClient.currentRoles().contains("ROLE_ADMIN")) {
            return "redirect:/admin";
        }
        return "redirect:" + UriComponentsBuilder.fromPath("/channel/{username}").buildAndExpand(username)
                .encode().toUriString();
    }
}
