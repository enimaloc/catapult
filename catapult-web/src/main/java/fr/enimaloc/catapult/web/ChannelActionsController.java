package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;
import java.util.Set;

/**
 * Mutation endpoints for the channel page. Every handler returns
 * {@link ResponseEntity}: a 204 No-Content when the caller is the
 * WS-routed client (sends {@code HX-Request: true}), a 302 redirect back
 * to the channel page for plain browser submits (degraded JS).
 *
 * <p>The WS path doesn't need the redirect because the UI updates from
 * the corresponding channel.viewed.* event published by catapult-api on
 * the underlying mutation.</p>
 */
@Controller
@RequestMapping("/channels/{username}")
@RequiredArgsConstructor
public class ChannelActionsController {

    private final ApiClient apiClient;

    // ── Binding actions ───────────────────────────────────────────────────────

    @PostMapping("/bindings/{id}/ccl-toggle")
    public ResponseEntity<Void> toggleCclEnabled(
            @PathVariable String username,
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean enabled,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channels/{username}/bindings/{id}/ccl-toggle",
                new CclToggleBody(enabled), username, id);
        return ackOrRedirect(hxRequest, username);
    }

    @PostMapping("/bindings/{id}/ignored-toggle")
    public ResponseEntity<Void> toggleIgnored(
            @PathVariable String username,
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean ignored,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channels/{username}/bindings/{id}/ignored-toggle",
                new IgnoredToggleBody(ignored), username, id);
        return ackOrRedirect(hxRequest, username);
    }

    @PostMapping("/bindings/{id}/delete")
    public ResponseEntity<Void> deleteBinding(
            @PathVariable String username,
            @PathVariable String id,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channels/{username}/bindings/{id}/delete", null, username, id);
        return ackOrRedirect(hxRequest, username);
    }

    @PostMapping("/bindings/{id}")
    public ResponseEntity<Void> updateBinding(
            @PathVariable String username,
            @PathVariable String id,
            @RequestParam(required = false) String twitchGameId,
            @RequestParam(required = false) String twitchGameName,
            @RequestParam(required = false) Set<String> ccls,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channels/{username}/bindings/{id}",
                new UpdateBindingBody(twitchGameId, twitchGameName, ccls), username, id);
        return ackOrRedirect(hxRequest, username);
    }

    @PostMapping("/bindings/{id}/tws")
    public ResponseEntity<Void> saveTws(
            @PathVariable String username,
            @PathVariable String id,
            @RequestParam(required = false) Set<String> tws,
            @RequestParam(defaultValue = "false") boolean twEnabled,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channel/bindings/{id}/tws",
                new TwSaveBody(tws == null ? Set.of() : tws), id);
        apiClient.post("/api/channel/bindings/{id}/tw-enabled",
                new TwEnabledBody(twEnabled), id);
        return ackOrRedirect(hxRequest, username);
    }

    @PostMapping("/bindings/{id}/tws/reset")
    public ResponseEntity<Void> resetTws(
            @PathVariable String username,
            @PathVariable String id,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channel/bindings/{id}/tws/reset", null, id);
        return ackOrRedirect(hxRequest, username);
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    @PostMapping("/settings/bot")
    public ResponseEntity<Void> toggleBot(
            @PathVariable String username,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channels/{username}/settings/bot", null, username);
        return ackOrRedirect(hxRequest, username);
    }

    @PostMapping("/game/recheck")
    public ResponseEntity<Void> recheckGame(
            @PathVariable String username,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channels/{username}/game/recheck", null, username);
        return ackOrRedirect(hxRequest, username);
    }

    @PostMapping("/settings/ccl")
    public ResponseEntity<Void> saveCclSettings(
            @PathVariable String username,
            @RequestParam(defaultValue = "false") boolean cclEnabled,
            @RequestParam(required = false) Set<String> blockedCcls,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channels/{username}/settings/ccl",
                new CclSettingsBody(cclEnabled, blockedCcls), username);
        return ackOrRedirect(hxRequest, username);
    }

    @PostMapping("/settings/tws")
    public ResponseEntity<Void> saveTwSettings(
            @PathVariable String username,
            @RequestParam(defaultValue = "false") boolean twEnabled,
            @RequestParam(required = false) Set<String> blockedTws,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channels/{username}/settings/tws",
                new TwSettingsBody(twEnabled, blockedTws), username);
        return ackOrRedirect(hxRequest, username);
    }

    @PostMapping("/settings/no-game")
    public ResponseEntity<Void> saveNoGameSettings(
            @PathVariable String username,
            @RequestParam(required = false) String twitchGameId,
            @RequestParam(required = false) String twitchGameName,
            @RequestParam(required = false) Set<String> ccls,
            @RequestParam(defaultValue = "false") boolean applyOnStreamStart,
            @RequestParam(defaultValue = "false") boolean applyOnNoGame,
            @RequestParam(defaultValue = "false") boolean applyOnStreamEnd,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channels/{username}/settings/no-game",
                new NoGameSettingsBody(twitchGameId, twitchGameName, ccls,
                        applyOnStreamStart, applyOnNoGame, applyOnStreamEnd), username);
        return ackOrRedirect(hxRequest, username);
    }

    @PostMapping("/settings/incomplete-fallback")
    public ResponseEntity<Void> saveIncompleteFallbackSettings(
            @PathVariable String username,
            @RequestParam(required = false) String twitchGameId,
            @RequestParam(required = false) String twitchGameName,
            @RequestParam(required = false) Set<String> ccls,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channels/{username}/settings/incomplete-fallback",
                new IncompleteFallbackBody(twitchGameId, twitchGameName, ccls), username);
        return ackOrRedirect(hxRequest, username);
    }

    @PostMapping("/settings/steam-personal-token")
    public ResponseEntity<Void> saveSteamPersonalToken(
            @PathVariable String username,
            @RequestParam String token,
            @RequestParam(defaultValue = "false") boolean shared,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channels/{username}/settings/steam-personal-token",
                new SteamTokenBody(token, shared), username);
        return ackOrRedirect(hxRequest, username);
    }

    @PostMapping("/settings/steam-personal-token/sharing")
    public ResponseEntity<Void> updateSteamTokenSharing(
            @PathVariable String username,
            @RequestParam boolean shared,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channels/{username}/settings/steam-personal-token/sharing",
                new SteamTokenSharingBody(shared), username);
        return ackOrRedirect(hxRequest, username);
    }

    @PostMapping("/settings/steam-personal-token/delete")
    public ResponseEntity<Void> deleteSteamPersonalToken(
            @PathVariable String username,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channels/{username}/settings/steam-personal-token/delete", null, username);
        return ackOrRedirect(hxRequest, username);
    }

    @PostMapping("/settings/steam/refresh-profile-cache")
    public ResponseEntity<Void> refreshSteamProfileCache(
            @PathVariable String username,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channels/{username}/steam/refresh-profile-cache", null, username);
        return ackOrRedirect(hxRequest, username);
    }

    @PostMapping("/settings/delete-account")
    public ResponseEntity<Void> deleteAccount(
            @PathVariable String username,
            @RequestParam String confirmUsername,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channels/{username}/settings/delete-account",
                new DeleteAccountBody(confirmUsername), username);
        return ackOrRedirect(hxRequest, username);
    }

    @PostMapping("/settings/cancel-deletion")
    public ResponseEntity<Void> cancelDeletion(
            @PathVariable String username,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channels/{username}/settings/cancel-deletion", null, username);
        return ackOrRedirect(hxRequest, username);
    }

    @PostMapping("/settings/minecraft")
    public String minecraftEnroll(@PathVariable String username,
                                  @RequestParam String minecraftName,
                                  RedirectAttributes redirectAttributes) {
        String name = minecraftName.trim();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            redirectAttributes.addFlashAttribute("minecraftError", "unknown");
            return "redirect:/channels/" + username;
        }
        ApiClient.ApiResult result = apiClient.minecraftEnroll(name);
        if (result.status() == 404) {
            redirectAttributes.addFlashAttribute("minecraftError", "unknown");
        } else if (result.status() == 503) {
            redirectAttributes.addFlashAttribute("minecraftError", "capacity");
        } else if (result.status() >= 400) {
            redirectAttributes.addFlashAttribute("minecraftError", "generic");
        }
        return "redirect:/channels/" + username;
    }

    @PostMapping("/settings/minecraft/check")
    public String minecraftCheck(@PathVariable String username) {
        apiClient.minecraftSyncNow();
        return "redirect:/channels/" + username;
    }

    @PostMapping("/settings/minecraft/disconnect")
    public String minecraftDisconnect(@PathVariable String username) {
        apiClient.minecraftUnenroll();
        return "redirect:/channels/" + username;
    }

    @PostMapping("/settings/disconnect")
    public ResponseEntity<Void> disconnectProvider(
            @PathVariable String username,
            @RequestParam String provider,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        apiClient.post("/api/channels/{username}/settings/disconnect",
                new DisconnectBody(provider), username);
        return ackOrRedirect(hxRequest, username);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * WS-routed callers set {@code HX-Request: true} and want a 204 (the UI
     * updates from the published channel.viewed.* event). Plain browser submits
     * still get the 302 redirect so the user lands back on the channel page.
     */
    private static ResponseEntity<Void> ackOrRedirect(String hxRequest, String username) {
        if ("true".equalsIgnoreCase(hxRequest)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/channels/" + username))
                .build();
    }

    // ── Request body records ──────────────────────────────────────────────────

    record CclToggleBody(boolean enabled) {}
    record IgnoredToggleBody(boolean ignored) {}
    record UpdateBindingBody(String twitchGameId, String twitchGameName, Set<String> ccls) {}
    record CclSettingsBody(boolean cclEnabled, Set<String> blockedCcls) {}
    record TwSettingsBody(boolean enabled, Set<String> blockedTws) {}
    record TwSaveBody(Set<String> tws) {}
    record TwEnabledBody(boolean enabled) {}
    record NoGameSettingsBody(String twitchGameId, String twitchGameName, Set<String> ccls,
                              boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd) {}
    record IncompleteFallbackBody(String twitchGameId, String twitchGameName, Set<String> ccls) {}
    record SteamTokenBody(String token, boolean shared) {}
    record SteamTokenSharingBody(boolean shared) {}
    record DeleteAccountBody(String confirmUsername) {}
    record DisconnectBody(String provider) {}
}
