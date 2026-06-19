package fr.enimaloc.catapult.security;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Démarre le flow OAuth Twitch pour rattacher un compte Twitch (le bot) au
 * {@code UserAccount} marqué {@code systemAccount=true}. L'admin clique
 * « Lier le Bot Twitch » dans catapult-web ; le browser est redirigé vers cet
 * endpoint (via nginx) qui pose l'ID du compte système dans la session
 * catapult-api avant le redirect OAuth.
 * <p>
 * La vérification {@code ROLE_ADMIN} se fait ici (session existante de l'admin)
 * et de nouveau dans {@link CatapultOAuth2UserService#handleBotLink} au callback
 * pour empêcher tout détournement.
 */
@Slf4j
@Controller
@Profile("!mock-web")
@RequiredArgsConstructor
public class BotLinkController {

    @Value("${app.web-url:http://localhost:8081}")
    private String webUrl;

    @GetMapping("/oauth2/start-bot-link")
    public String startBotLink(@RequestParam("systemAccountId") String systemAccountId,
                                HttpSession session,
                                Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || !isAdmin(auth)) {
            log.warn("Bot link attempt rejected: caller is not an authenticated admin");
            return "redirect:" + webUrl + "/login?error";
        }
        session.setAttribute("bot-link-pending", systemAccountId);
        return "redirect:/oauth2/authorization/twitch";
    }

    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
