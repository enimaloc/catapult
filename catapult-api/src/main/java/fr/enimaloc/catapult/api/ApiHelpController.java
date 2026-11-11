package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.HelpContent;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.drops.DropsGate;
import fr.enimaloc.catapult.service.minecraft.MinecraftGateService;
import fr.enimaloc.catapult.service.title.StreamTitleService;
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
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.INTERNAL + "/help-cards")
@RequiredArgsConstructor
public class ApiHelpController {

    private final MessageSource messageSource;
    private final MinecraftGateService gateService;
    private final StreamTitleService streamTitleService;
    private final DropsGate dropsGate;
    private final UserAccountRepository userAccountRepository;

    @Value("${app.account.deletion-delay-days:7}")
    private int accountDeletionDelayDays;

    @Value("${twitch.default-no-game.name:}")
    private String defaultNoGameName;

    @Value("${spring.application.name:Catapult}")
    private String appName;

    @Value("${xbox.enabled:false}")
    private boolean xboxEnabled;

    @GetMapping("/{cardId}")
    public HelpContent help(@PathVariable String cardId, Locale locale,
                            @AuthenticationPrincipal Jwt jwt) {
        Object[] args = {accountDeletionDelayDays, defaultNoGameName, appName};
        String title = messageSource.getMessage("help." + cardId + ".title", null, cardId, locale);
        String body = messageSource.getMessage("help." + cardId + ".body", args, "", locale);
        if ("connections".equals(cardId)) {
            if (xboxEnabled) {
                body += messageSource.getMessage("help.connections.body.xbox", args, "", locale);
            }
            if (isMinecraftVisible(jwt)) {
                body += messageSource.getMessage("help.connections.body.minecraft", args, "", locale);
            }
        }
        if ("title-settings".equals(cardId)) {
            // Read-only, as the title fields offered them (the settings page enrols): the drops need the conditions
            Optional<UserAccount> account = account(jwt);
            if (account.map(streamTitleService::conditionsAvailableReadOnly).orElse(false)) {
                body += messageSource.getMessage("help.title-settings.body.conditions", args, "", locale);
                if (dropsGate.isAvailableReadOnly(account.get())) {
                    body += messageSource.getMessage("help.title-settings.body.drops", args, "", locale);
                }
            }
        }
        return new HelpContent(title, body);
    }

    private Optional<UserAccount> account(Jwt jwt) {
        if (jwt == null) return Optional.empty();
        try {
            return userAccountRepository.findById(UUID.fromString(jwt.getSubject()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private boolean isMinecraftVisible(Jwt jwt) {
        if (jwt == null) return false;
        try {
            return gateService.isAvailableForAccount(UUID.fromString(jwt.getSubject()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
