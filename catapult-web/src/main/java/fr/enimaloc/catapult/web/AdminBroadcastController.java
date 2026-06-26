package fr.enimaloc.catapult.web;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

/**
 * Admin Thymeleaf page for issuing broadcasts (spec §8 UI admin).
 *
 * <p>The page itself is plain Thymeleaf; the «send» button invokes the
 * {@code admin.broadcast.send} WebSocket action which proxies to
 * {@code POST /api/admin/broadcast}.</p>
 */
@Controller
@RequestMapping("/admin/broadcast")
@RequiredArgsConstructor
public class AdminBroadcastController {

    /**
     * Names exposed in the dropdown. Mirrors {@code BroadcastValidator.ALLOWED_NAMES}
     * in catapult-api — kept here as a fixed list so the page stays trivially
     * server-renderable without a round-trip.
     */
    public static final List<String> BROADCAST_NAMES = List.of(
            "maintenance.scheduled",
            "maintenance.cancelled",
            "version.deployed",
            "alert.info",
            "alert.warning"
    );

    @GetMapping
    public String page(Model model) {
        model.addAttribute("broadcastNames", BROADCAST_NAMES);
        return "admin/broadcast";
    }
}
