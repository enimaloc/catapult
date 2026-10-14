package fr.enimaloc.catapult.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.util.Locale;

/**
 * Full-page loads: the index shell with the requested page already rendered in #app (see
 * {@link SpaFragmentController} for the fragments served on client-side navigation).
 */
@Controller
@RequiredArgsConstructor
public class IndexController {
    private final ModelFiller filler;

    @GetMapping
    String index(Model model, Locale locale, HttpServletResponse response) throws IOException {
        return index(model, "", locale, response);
    }

    @GetMapping("/{page}")
    String index(Model model, @PathVariable String page, Locale locale, HttpServletResponse response)
            throws IOException {
        boolean unknownPage = !SiteCatalog.isPage(page);
        model.addAttribute("error", unknownPage);
        if (unknownPage) {
            // A real 404, not a page saying so: search engines would index it as a page otherwise.
            response.setStatus(HttpStatus.NOT_FOUND.value());
            filler.error(model, HttpStatus.NOT_FOUND.value());
        }
        filler.fill(model, page, locale);
        return "index";
    }

    /** The admin page, for admins only: anyone else gets the 401 / 403 error page in its place. */
    @GetMapping("/admin")
    String admin(Model model, Locale locale, HttpServletResponse response) throws IOException {
        int refused = AdminAccess.refusal(filler);
        model.addAttribute("error", refused != 0);
        if (refused != 0) {
            response.setStatus(refused);
            filler.error(model, refused);
        } else {
            filler.admin(model);
        }
        filler.fill(model, "admin", locale);
        return "index";
    }

    @GetMapping("/channel/{username}")
    String channel(Model model, @PathVariable String username,
                   @RequestParam(defaultValue = "0") int page,
                   @RequestParam(required = false) String status,
                   @RequestParam(required = false) String source,
                   Locale locale) {
        filler.channel(model, username, page, status, source);
        filler.defaultAttr(model, "channel", locale);
        return "index";
    }
}
