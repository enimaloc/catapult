package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.connect.SteamStartResponse;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Linking the member's Steam account (the Steam card's connect and disconnect buttons, see
 * channel-connections.js). Connecting runs in a popup: {@code /connect/steam} sends it to Steam's
 * OpenID login through catapult-api, whose callback sends it back to {@code /connect/steam/close},
 * which tells the outcome and closes it; the card follows from the connection's event.
 */
@Controller
@RequestMapping("/connect/steam")
@RequiredArgsConstructor
public class SteamConnectController {
    /** Where catapult-api sends the browser to log in to Steam; nothing else is followed. */
    static final String STEAM_OPENID_LOGIN = "https://steamcommunity.com/openid/login?";

    private final ApiClient apiClient;

    /** Off to Steam's login, as catapult-api prepared it; the popup's closing page when that fails. */
    @GetMapping
    public String connect() {
        if (!ApiClient.hasSession()) return "redirect:/connect/steam/close?error=session";
        SteamStartResponse start = apiClient.get("/api/connect/steam/start", SteamStartResponse.class);
        String url = start == null ? null : start.redirectUrl();
        if (url == null || !url.startsWith(STEAM_OPENID_LOGIN)) return "redirect:/connect/steam/close?error=unavailable";
        return "redirect:" + url;
    }

    /** The popup's last page: tells whether the link worked (catapult-api says why not, as {@code error}). */
    @GetMapping("/close")
    public String close(@RequestParam(required = false) String error, Model model) {
        model.addAttribute("steamError", error != null);
        return "pages/steam-close";
    }

    /** Unlinks the member's Steam account: catapult-api's status, 204 when done (502 when unreachable). */
    @PostMapping("/disconnect")
    @ResponseBody
    public ResponseEntity<Void> disconnect() {
        if (!ApiClient.hasSession()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        int status = apiClient.call(HttpMethod.POST, "/api/connect/steam/disconnect", null, Void.class).status();
        return ResponseEntity.status(status >= 200 && status < 300 ? HttpStatus.NO_CONTENT.value() : status).build();
    }
}
