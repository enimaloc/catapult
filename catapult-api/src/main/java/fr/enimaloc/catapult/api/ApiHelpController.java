package fr.enimaloc.catapult.api;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/api/help")
@RequiredArgsConstructor
public class ApiHelpController {

    private final MessageSource messageSource;

    @Value("${app.account.deletion-delay-days:7}")
    private int accountDeletionDelayDays;

    @Value("${twitch.default-no-game.name:}")
    private String defaultNoGameName;

    @Value("${spring.application.name:Catapult}")
    private String appName;

    @GetMapping("/{cardId}")
    public HelpContent help(@PathVariable String cardId, Locale locale) {
        Object[] args = {accountDeletionDelayDays, defaultNoGameName, appName};
        String title = messageSource.getMessage("help." + cardId + ".title", null, cardId, locale);
        String body = messageSource.getMessage("help." + cardId + ".body", args, "", locale);
        return new HelpContent(title, body);
    }

    public record HelpContent(String title, String body) {}
}
