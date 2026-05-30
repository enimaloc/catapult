package fr.enimaloc.catapult.web;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Locale;

@Controller
@RequiredArgsConstructor
public class AppController {

    private final MessageSource messageSource;

    @Value("${app.account.deletion-delay-days}")
    private int accountDeletionDelayDays;

    @Value("${twitch.default-no-game.name}")
    private String defaultNoGameName;

    @Value("${spring.application.name}")
    private String appName;

    // -------------------------------------------------------------------------
    // Old URL redirects
    // -------------------------------------------------------------------------

    @GetMapping({"/app", "/dashboard", "/settings"})
    public String redirectToChannels() {
        return "redirect:/channels";
    }

    @GetMapping("/bindings")
    public String redirectBindings() {
        return "redirect:/channels";
    }

    // -------------------------------------------------------------------------
    // Help panel (no channel scoping needed)
    // -------------------------------------------------------------------------

    @GetMapping("/help/{cardId}")
    public String helpPanel(@PathVariable String cardId, Model model, Locale locale) {
        Object[] args = {accountDeletionDelayDays, defaultNoGameName, appName};
        model.addAttribute("helpTitle", messageSource.getMessage("help." + cardId + ".title", null, cardId, locale));
        model.addAttribute("helpBody", messageSource.getMessage("help." + cardId + ".body", args, "", locale));
        return "fragments/help-content :: help-content";
    }
}
