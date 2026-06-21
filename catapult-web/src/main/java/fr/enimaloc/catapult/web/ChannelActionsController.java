package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Set;

@Controller
@RequestMapping("/channels/{username}")
@RequiredArgsConstructor
public class ChannelActionsController {

    private final ApiClient apiClient;

    // ── Binding actions ───────────────────────────────────────────────────────

    @PostMapping("/bindings/{id}/ccl-toggle")
    public String toggleCclEnabled(
            @PathVariable String username,
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean enabled) {
        apiClient.post("/api/channels/{username}/bindings/{id}/ccl-toggle",
                new CclToggleBody(enabled), username, id);
        return "redirect:/channels/" + username;
    }

    @PostMapping("/bindings/{id}/ignored-toggle")
    public String toggleIgnored(
            @PathVariable String username,
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean ignored) {
        apiClient.post("/api/channels/{username}/bindings/{id}/ignored-toggle",
                new IgnoredToggleBody(ignored), username, id);
        return "redirect:/channels/" + username;
    }

    @PostMapping("/bindings/{id}/delete")
    public String deleteBinding(
            @PathVariable String username,
            @PathVariable String id) {
        apiClient.post("/api/channels/{username}/bindings/{id}/delete", null, username, id);
        return "redirect:/channels/" + username;
    }

    @PostMapping("/bindings/{id}")
    public String updateBinding(
            @PathVariable String username,
            @PathVariable String id,
            @RequestParam(required = false) String twitchGameId,
            @RequestParam(required = false) String twitchGameName,
            @RequestParam(required = false) Set<String> ccls) {
        apiClient.post("/api/channels/{username}/bindings/{id}",
                new UpdateBindingBody(twitchGameId, twitchGameName, ccls), username, id);
        return "redirect:/channels/" + username;
    }

    @PostMapping("/bindings/{id}/tws")
    public String saveTws(
            @PathVariable String username,
            @PathVariable String id,
            @RequestParam(required = false) Set<String> tws,
            @RequestParam(defaultValue = "false") boolean twEnabled) {
        apiClient.post("/api/channel/bindings/{id}/tws",
                new TwSaveBody(tws == null ? Set.of() : tws), id);
        apiClient.post("/api/channel/bindings/{id}/tw-enabled",
                new TwEnabledBody(twEnabled), id);
        return "redirect:/channels/" + username;
    }

    @PostMapping("/bindings/{id}/tws/reset")
    public String resetTws(
            @PathVariable String username,
            @PathVariable String id) {
        apiClient.post("/api/channel/bindings/{id}/tws/reset", null, id);
        return "redirect:/channels/" + username;
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    @PostMapping("/settings/bot")
    public String toggleBot(@PathVariable String username) {
        apiClient.post("/api/channels/{username}/settings/bot", null, username);
        return "redirect:/channels/" + username;
    }

    @PostMapping("/settings/ccl")
    public String saveCclSettings(
            @PathVariable String username,
            @RequestParam(defaultValue = "false") boolean cclEnabled,
            @RequestParam(required = false) Set<String> blockedCcls) {
        apiClient.post("/api/channels/{username}/settings/ccl",
                new CclSettingsBody(cclEnabled, blockedCcls), username);
        return "redirect:/channels/" + username;
    }

    @PostMapping("/settings/tws")
    public String saveTwSettings(
            @PathVariable String username,
            @RequestParam(defaultValue = "false") boolean twEnabled,
            @RequestParam(required = false) Set<String> blockedTws) {
        apiClient.post("/api/channels/{username}/settings/tws",
                new TwSettingsBody(twEnabled, blockedTws), username);
        return "redirect:/channels/" + username;
    }

    @PostMapping("/settings/no-game")
    public String saveNoGameSettings(
            @PathVariable String username,
            @RequestParam(required = false) String twitchGameId,
            @RequestParam(required = false) String twitchGameName,
            @RequestParam(required = false) Set<String> ccls,
            @RequestParam(defaultValue = "false") boolean applyOnStreamStart,
            @RequestParam(defaultValue = "false") boolean applyOnNoGame,
            @RequestParam(defaultValue = "false") boolean applyOnStreamEnd) {
        apiClient.post("/api/channels/{username}/settings/no-game",
                new NoGameSettingsBody(twitchGameId, twitchGameName, ccls,
                        applyOnStreamStart, applyOnNoGame, applyOnStreamEnd), username);
        return "redirect:/channels/" + username;
    }

    @PostMapping("/settings/incomplete-fallback")
    public String saveIncompleteFallbackSettings(
            @PathVariable String username,
            @RequestParam(required = false) String twitchGameId,
            @RequestParam(required = false) String twitchGameName,
            @RequestParam(required = false) Set<String> ccls) {
        apiClient.post("/api/channels/{username}/settings/incomplete-fallback",
                new IncompleteFallbackBody(twitchGameId, twitchGameName, ccls), username);
        return "redirect:/channels/" + username;
    }

    @PostMapping("/settings/steam-personal-token")
    public String saveSteamPersonalToken(
            @PathVariable String username,
            @RequestParam String token,
            @RequestParam(defaultValue = "false") boolean shared) {
        apiClient.post("/api/channels/{username}/settings/steam-personal-token",
                new SteamTokenBody(token, shared), username);
        return "redirect:/channels/" + username;
    }

    @PostMapping("/settings/steam-personal-token/sharing")
    public String updateSteamTokenSharing(
            @PathVariable String username,
            @RequestParam boolean shared) {
        apiClient.post("/api/channels/{username}/settings/steam-personal-token/sharing",
                new SteamTokenSharingBody(shared), username);
        return "redirect:/channels/" + username;
    }

    @PostMapping("/settings/steam-personal-token/delete")
    public String deleteSteamPersonalToken(@PathVariable String username) {
        apiClient.post("/api/channels/{username}/settings/steam-personal-token/delete", null, username);
        return "redirect:/channels/" + username;
    }

    @PostMapping("/settings/delete-account")
    public String deleteAccount(
            @PathVariable String username,
            @RequestParam String confirmUsername) {
        apiClient.post("/api/channels/{username}/settings/delete-account",
                new DeleteAccountBody(confirmUsername), username);
        return "redirect:/channels/" + username;
    }

    @PostMapping("/settings/cancel-deletion")
    public String cancelDeletion(@PathVariable String username) {
        apiClient.post("/api/channels/{username}/settings/cancel-deletion", null, username);
        return "redirect:/channels/" + username;
    }

    @PostMapping("/settings/disconnect")
    public String disconnectProvider(
            @PathVariable String username,
            @RequestParam String provider) {
        apiClient.post("/api/channels/{username}/settings/disconnect",
                new DisconnectBody(provider), username);
        return "redirect:/channels/" + username;
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
