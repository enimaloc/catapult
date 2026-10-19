package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.service.http.ApiClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.util.List;
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
    private final NavEventStreamController navEvents;

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

    /** The navbar's channels, which spa.js loads once the page is shown; their live updates follow them. */
    @GetMapping("/nav-channels")
    String navChannels(Model model, HttpServletRequest request) {
        List<ChannelDto> channels = filler.navChannels(model);
        HttpSession session = SessionStreams.loggedIn(request);
        if (session != null) {
            navEvents.remember(session, channels);
        }
        return "fragments/nav-channels :: channels";
    }

    @GetMapping("/channel/{username}")
    String channel(Model model, @PathVariable String username,
                   @RequestParam(defaultValue = "0") int page,
                   @RequestParam(required = false) String status,
                   @RequestParam(required = false) String source, HttpServletResponse response) {
        int refused = filler.channel(model, username, page, status, source);
        if (refused != 0) {
            response.setStatus(refused);
            filler.error(model, refused);
            return "pages/error :: error";
        }
        filler.defaultAttr(model, "channel");
        return "pages/channel :: channel";
    }

    @GetMapping("/admin")
    String admin(Model model, HttpServletResponse response) {
        int refused = AdminAccess.refusal(filler);
        if (refused != 0) {
            response.setStatus(refused);
            filler.error(model, refused);
            return "pages/error :: error";
        }
        filler.admin(model);
        filler.defaultAttr(model, "admin");
        return "pages/admin :: admin";
    }

    /** The notifications page; only "logged in" matters, every member sees their own. */
    @GetMapping("/notifications")
    String notifications(Model model, @RequestParam(defaultValue = "0") int page, HttpServletResponse response) {
        if (!ApiClient.hasSession()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            filler.error(model, HttpStatus.UNAUTHORIZED.value());
            return "pages/error :: error";
        }
        filler.notifications(model, Math.max(page, 0));
        filler.defaultAttr(model, "notifications");
        model.addAttribute("page", Math.max(page, 0));
        return "pages/notifications :: notifications";
    }

    @GetMapping("/login")
    String login(Model model, @RequestParam(required = false) String error) {
        String channel = ModelFiller.loggedInChannelPath();
        if (channel != null) {
            return "redirect:/spa" + channel;
        }
        filler.login(model, error);
        filler.defaultAttr(model, "login");
        return "pages/login :: login";
    }

    @GetMapping("/join")
    String join(Model model, @RequestParam(required = false) String invite,
                @RequestParam(required = false) String error) {
        String channel = ModelFiller.loggedInChannelPath();
        if (channel != null) {
            return "redirect:/spa" + channel;
        }
        filler.join(model, invite, error);
        filler.defaultAttr(model, "join");
        return "pages/join :: join";
    }

    @GetMapping("/{page}")
    String unknown(Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        filler.error(model, HttpStatus.NOT_FOUND.value());
        return "pages/error :: error";
    }
}
