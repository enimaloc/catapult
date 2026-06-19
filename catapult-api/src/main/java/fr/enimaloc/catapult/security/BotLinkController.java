package fr.enimaloc.catapult.security;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;

/**
 * Démarre le flow OAuth Twitch pour rattacher un compte Twitch (le bot) au
 * {@code UserAccount} marqué {@code systemAccount=true}. L'admin clique
 * « Lier le Bot Twitch » dans catapult-web ; le browser est redirigé vers cet
 * endpoint (via nginx) qui pose l'ID du compte système dans la session
 * catapult-api avant le redirect OAuth.
 * <p>
 * <strong>Sécurité</strong> : Pas de check d'auth en entrée car l'admin est
 * authentifié via JWT côté catapult-web mais pas nécessairement par session
 * côté catapult-api (sessions séparées entre apps). L'enforcement est en deux
 * couches :
 * <ol>
 *   <li>{@link CatapultOAuth2UserService#handleBotLink} vérifie {@code ROLE_ADMIN}
 *       <em>avant</em> toute modification de state.</li>
 *   <li>L'attribut session {@code bot-link-pending} a un TTL court
 *       ({@value #PENDING_TTL_SECONDS}s) pour qu'un attaquant trompant un admin
 *       en visitant ce lien ne puisse pas lier l'admin à un mauvais OAuth flow
 *       déclenché plusieurs minutes plus tard.</li>
 * </ol>
 * Toute valeur précédente est écrasée pour ne pas piggy-back sur un flow
 * antérieur.
 */
@Slf4j
@Controller
@Profile("!mock-web")
@RequiredArgsConstructor
public class BotLinkController {

    public static final String SESSION_ATTR = "bot-link-pending";
    public static final String SESSION_EXPIRES_ATTR = "bot-link-pending-expires-at";
    public static final long PENDING_TTL_SECONDS = 300L; // 5 min

    @GetMapping("/oauth2/start-bot-link")
    public String startBotLink(@RequestParam("systemAccountId") String systemAccountId,
                                HttpSession session) {
        session.setAttribute(SESSION_ATTR, systemAccountId);
        session.setAttribute(SESSION_EXPIRES_ATTR,
            Instant.now().plusSeconds(PENDING_TTL_SECONDS).toEpochMilli());
        log.info("Bot link flow started for system account {}", systemAccountId);
        return "redirect:/oauth2/authorization/twitch";
    }
}
