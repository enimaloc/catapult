package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.HelpContent;
import fr.enimaloc.catapult.service.emergency.EmergencyProperties;
import fr.enimaloc.catapult.service.minecraft.MinecraftGateService;
import fr.enimaloc.catapult.service.title.TitleHelpSections;
import fr.enimaloc.catapult.service.twitch.SystemTwitchAccountService;
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
@RequestMapping(ApiPaths.INTERNAL + "/help-cards")
@RequiredArgsConstructor
public class ApiHelpController {

    private final MessageSource messageSource;
    private final MinecraftGateService gateService;
    private final TitleHelpSections titleHelpSections;
    private final SystemTwitchAccountService systemAccount;
    private final EmergencyProperties emergencyProperties;

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
        if ("status".equals(cardId)) {
            // {3}: the bot's Twitch login, as streamers type it in chat (@login off / on / emergency).
            // {4}: how many hours an emergency stop lasts.
            Object[] chatArgs = {accountDeletionDelayDays, defaultNoGameName, appName, botLogin(locale),
                emergencyProperties.getStopDuration().toHours()};
            body += messageSource.getMessage("help.status.body.chat", chatArgs, "", locale);
        }
        if ("connections".equals(cardId)) {
            if (xboxEnabled) {
                body += messageSource.getMessage("help.connections.body.xbox", args, "", locale);
            }
            if (isMinecraftVisible(jwt)) {
                body += messageSource.getMessage("help.connections.body.minecraft", args, "", locale);
            }
        }
        if ("title-settings".equals(cardId)) {
            TitleHelpSections.Offered offered = titleSections(jwt);
            if (offered.conditions()) {
                body += messageSource.getMessage("help.title-settings.body.conditions", args, "", locale);
            }
            if (offered.drops()) {
                body += messageSource.getMessage("help.title-settings.body.drops", args, "", locale);
            }
        }
        return new HelpContent(title, body);
    }

    private String botLogin(Locale locale) {
        String login = systemAccount.getSystemTwitchUsername();
        if (login != null && !login.isBlank()) return login;
        return messageSource.getMessage("help.status.bot_fallback", null, "the bot", locale);
    }

    private TitleHelpSections.Offered titleSections(Jwt jwt) {
        if (jwt == null) return TitleHelpSections.Offered.NONE;
        try {
            return titleHelpSections.forAccount(UUID.fromString(jwt.getSubject()));
        } catch (IllegalArgumentException e) {
            return TitleHelpSections.Offered.NONE;
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
