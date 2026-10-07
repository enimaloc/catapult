package fr.enimaloc.catapult.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.util.Locale;

/**
 * The fragments spa.js swaps into #app on client-side navigation — one per
 * {@link SiteCatalog#PAGES} entry's {@code templateUrl}, plus a 404 fragment for anything else.
 */
@Controller
@RequestMapping("/spa")
@RequiredArgsConstructor
public class SpaFragmentController {
    private final ModelFiller filler;

    @GetMapping("/landing")
    String landing(Model model) {
        filler.defaultAttr(model, "");
        return "pages/landing :: landing";
    }

    @GetMapping("/privacy")
    String privacy(Model model, Locale locale) throws IOException {
        filler.privacy(model, locale);
        return "pages/privacy :: privacy";
    }

    @GetMapping("/channels")
    String channels(Model model) {
        filler.channels(model);
        return "pages/channels :: channels";
    }

    @GetMapping("/channel/{username}")
    String channel(Model model, @PathVariable String username,
                   @RequestParam(defaultValue = "0") int page,
                   @RequestParam(required = false) String status,
                   @RequestParam(required = false) String source) {
        filler.channel(model, username, page, status, source);
        filler.defaultAttr(model, "channel");
        return "pages/channel :: channel";
    }

    @GetMapping("/{page}")
    String unknown(Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        filler.error(model, HttpStatus.NOT_FOUND.value());
        return "pages/error :: error";
    }
}
