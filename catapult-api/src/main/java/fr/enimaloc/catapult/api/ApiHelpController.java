package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.MinecraftGateService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/help")
@RequiredArgsConstructor
public class ApiHelpController {

    private final MessageSource messageSource;
    private final MinecraftGateService gateService;
    private final UserAccountRepository userAccountRepository;

    @Value("${app.account.deletion-delay-days:7}")
    private int accountDeletionDelayDays;

    @Value("${twitch.default-no-game.name:}")
    private String defaultNoGameName;

    @Value("${spring.application.name:Catapult}")
    private String appName;

    @GetMapping("/{cardId}")
    public HelpContent help(@PathVariable String cardId, Locale locale,
                            @AuthenticationPrincipal Jwt jwt) {
        Object[] args = {accountDeletionDelayDays, defaultNoGameName, appName};
        String title = messageSource.getMessage("help." + cardId + ".title", null, cardId, locale);
        String body = messageSource.getMessage("help." + cardId + ".body", args, "", locale);
        if ("connections".equals(cardId) && isMinecraftVisible(jwt)) {
            body += messageSource.getMessage("help.connections.body.minecraft", args, "", locale);
        }
        return new HelpContent(title, body);
    }

    private boolean isMinecraftVisible(Jwt jwt) {
        if (jwt == null) return false;
        try {
            return userAccountRepository.findById(UUID.fromString(jwt.getSubject()))
                    .map(gateService::isAvailableFor)
                    .orElse(false);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public record HelpContent(String title, String body) {}
}
