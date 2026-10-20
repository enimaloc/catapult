package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.dto.index.SpaPage;
import fr.enimaloc.catapult.service.http.ApiClient;
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

    // No page name has a dot: dotted names are WellKnownServeController's root files. A dynamic page
    // (/channel/{username}) without its segment is no page either.
    @GetMapping("/{page:[^.]+}")
    String index(Model model, @PathVariable String page, Locale locale, HttpServletResponse response)
            throws IOException {
        boolean unknownPage = !SiteCatalog.isPage(page) || SiteCatalog.page(page).map(SpaPage::isDynamic).orElse(false);
        model.addAttribute("error", unknownPage);
        if (unknownPage) {
            // A real 404, not a page saying so: search engines would index it as a page otherwise.
            response.setStatus(HttpStatus.NOT_FOUND.value());
            filler.error(model, HttpStatus.NOT_FOUND.value());
        }
        filler.fill(model, page, locale);
        return "index";
    }

    /** Where catapult-api sends a failed login; a member already logged in goes to their channel. */
    @GetMapping("/login")
    String login(Model model, @RequestParam(required = false) String error, Locale locale) throws IOException {
        String channel = ModelFiller.loggedInChannelPath();
        if (channel != null) {
            return "redirect:" + channel;
        }
        model.addAttribute("error", false);
        filler.login(model, error);
        filler.fill(model, "login", locale);
        return "index";
    }

    /** Where catapult-api sends an invitation's link and its failures; a logged-in member goes to their channel. */
    @GetMapping("/join")
    String join(Model model, @RequestParam(required = false) String invite,
                @RequestParam(required = false) String error, Locale locale) throws IOException {
        String channel = ModelFiller.loggedInChannelPath();
        if (channel != null) {
            return "redirect:" + channel;
        }
        model.addAttribute("error", false);
        filler.join(model, invite, error);
        filler.fill(model, "join", locale);
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

    /** The member's notification history; a visitor who is logged out gets the 401 error page in its place. */
    @GetMapping("/notifications")
    String notifications(Model model, @RequestParam(defaultValue = "0") int page, Locale locale,
                         HttpServletResponse response) throws IOException {
        boolean refused = !ApiClient.hasSession();
        model.addAttribute("error", refused);
        if (refused) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            filler.error(model, HttpStatus.UNAUTHORIZED.value());
        } else {
            filler.notifications(model, Math.max(page, 0));
        }
        filler.fill(model, "notifications", locale);
        return "index";
    }

    @GetMapping("/channel/{username}")
    String channel(Model model, @PathVariable String username,
                   @RequestParam(defaultValue = "0") int page,
                   @RequestParam(required = false) String status,
                   @RequestParam(required = false) String source,
                   Locale locale, HttpServletResponse response) {
        int refused = filler.channel(model, username, page, status, source);
        model.addAttribute("error", refused != 0);
        if (refused != 0) {
            response.setStatus(refused);
            filler.error(model, refused);
        }
        filler.defaultAttr(model, "channel", locale);
        return "index";
    }
}
