package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.HelpContent;
import fr.enimaloc.catapult.service.ApiService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A card's help as a page of its own, {@code /help/{cardId}} (and spa.js's fragment of it,
 * {@code /spa/help-page/{cardId}}), for a link to it: the same text as the side sheet a card's "?"
 * opens (see SpaFragmentController#help), in the visitor's language. An unknown card, or one
 * catapult-api doesn't answer for, is a 404; so is {@code /help} alone.
 */
@Controller
@RequiredArgsConstructor
public class HelpPageController {
    /** The card ids catapult-api knows look like this; the id reaches a catapult-api path. */
    static final Pattern CARD_ID = Pattern.compile("[a-z-]{1,40}");

    private final ModelFiller filler;
    private final ApiService apiService;

    @GetMapping("/help/{cardId}")
    String page(Model model, @PathVariable String cardId, Locale locale, HttpServletResponse response) throws IOException {
        boolean found = help(model, cardId, locale);
        model.addAttribute("error", !found);
        if (!found) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            filler.error(model, HttpStatus.NOT_FOUND.value());
        }
        filler.fill(model, "help", locale);
        return "index";
    }

    @GetMapping("/spa/help-page/{cardId}")
    String fragment(Model model, @PathVariable String cardId, Locale locale, HttpServletResponse response) {
        if (!help(model, cardId, locale)) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            filler.error(model, HttpStatus.NOT_FOUND.value());
            return "pages/error :: error";
        }
        filler.defaultAttr(model, "help");
        return "pages/help :: help";
    }

    /** {@code /help} without a card: nothing to show. */
    @GetMapping("/help")
    String nonePage(Model model, Locale locale, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        model.addAttribute("error", true);
        filler.error(model, HttpStatus.NOT_FOUND.value());
        filler.fill(model, "help", locale);
        return "index";
    }

    @GetMapping("/spa/help-page")
    String noneFragment(Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        filler.error(model, HttpStatus.NOT_FOUND.value());
        return "pages/error :: error";
    }

    /** Puts the card's help in the model; false when the id is malformed or catapult-api has none. */
    private boolean help(Model model, String cardId, Locale locale) {
        if (!CARD_ID.matcher(cardId).matches()) return false;
        HelpContent help = apiService.help(cardId, locale);
        if (help == null) return false;
        model.addAttribute("helpTitle", help.title());
        model.addAttribute("helpBody", help.body());
        return true;
    }
}
