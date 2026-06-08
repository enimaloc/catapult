package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Receives the JWT from catapult-api after a successful OAuth2 login and stores it in the session.
 * catapult-api redirects here as: GET /auth/callback?token=<JWT>
 *
 * The login redirect uses a path-relative URL (/oauth2/authorization/twitch) that nginx-router
 * proxies internally to catapult-api, so the browser never touches catapult-api:8080 directly.
 */
@Controller
public class AuthCallbackController {

    @GetMapping("/auth/callback")
    public String callback(@RequestParam String token, HttpSession session) {
        session.setAttribute(ApiClient.SESSION_JWT_KEY, token);
        return "redirect:/channels";
    }

    @GetMapping("/login")
    public String login() {
        return "redirect:/oauth2/authorization/twitch";
    }

    @GetMapping("/logout-redirect")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/";
    }
}
