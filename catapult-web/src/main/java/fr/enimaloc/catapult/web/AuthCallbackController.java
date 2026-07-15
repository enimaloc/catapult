package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Handles the post-OAuth2 callback from catapult-api.
 * catapult-api redirects here with a short-lived one-time code (not the JWT itself).
 * This controller exchanges the code for a JWT server-to-server, keeping the JWT out of URLs.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AuthCallbackController {

    private final ApiClient apiClient;

    @GetMapping("/auth/callback")
    public String callback(@RequestParam String code, HttpSession session) {
        String jwt = apiClient.exchangeCode(code);
        if (jwt == null) {
            log.warn("Auth code exchange failed or expired");
            return "redirect:/login?error";
        }
        session.setAttribute(ApiClient.SESSION_JWT_KEY, jwt);
        return "redirect:/channels";
    }

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error, Model model) {
        if (error == null) {
            return "redirect:/oauth2/authorization/twitch";
        }
        model.addAttribute("loginError", error.isBlank() ? "generic" : error);
        return "login";
    }

    @GetMapping("/logout-redirect")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/";
    }
}
