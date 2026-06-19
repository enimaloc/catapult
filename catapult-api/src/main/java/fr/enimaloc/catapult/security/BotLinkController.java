package fr.enimaloc.catapult.security;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
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
 * Pas de check d'auth ici car l'admin est authentifié côté catapult-web via JWT
 * mais pas nécessairement côté catapult-api (sessions séparées). La vérification
 * {@code ROLE_ADMIN} se fait à la fin du flow dans
 * {@link CatapultOAuth2UserService#handleBotLink} (avant toute modification de
 * state). Worst case : un attaquant déclenche un flow qui sera rejeté au retour.
 */
@Slf4j
@Controller
@Profile("!mock-web")
@RequiredArgsConstructor
public class BotLinkController {

    @GetMapping("/oauth2/start-bot-link")
    public String startBotLink(@RequestParam("systemAccountId") String systemAccountId,
                                HttpSession session) {
        session.setAttribute("bot-link-pending", systemAccountId);
        log.info("Bot link flow started for system account {}", systemAccountId);
        return "redirect:/oauth2/authorization/twitch";
    }
}
