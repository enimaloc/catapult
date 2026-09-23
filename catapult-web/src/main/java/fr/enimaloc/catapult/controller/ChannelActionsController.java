package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.CclSettingsRequest;
import fr.enimaloc.catapult.common.dto.CclToggleRequest;
import fr.enimaloc.catapult.common.dto.IgnoredToggleRequest;
import fr.enimaloc.catapult.common.dto.LinkStateResponse;
import fr.enimaloc.catapult.common.dto.SaveBody;
import fr.enimaloc.catapult.common.dto.SteamTokenRequest;
import fr.enimaloc.catapult.common.dto.SteamTokenSharingRequest;
import fr.enimaloc.catapult.common.dto.TwEnabledBody;
import fr.enimaloc.catapult.common.dto.TwSettingsRequest;
import fr.enimaloc.catapult.common.dto.UpdateBindingRequest;
import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browser-facing mutation endpoints for the channel dashboard page. These exist because
 * the session JWT ApiClient authenticates with never reaches the browser (see ApiClient's
 * HttpSession lookup) — the browser can only call back into catapult-web itself, which
 * then forwards to catapult-api with the server-held JWT via ApiService.
 */
@RestController
@RequiredArgsConstructor
public class ChannelActionsController {
    private final ApiService apiService;

    @PostMapping("/channel/{username}/settings/bot")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleBot(@PathVariable String username) {
        apiService.toggleBot(username);
    }

    @PostMapping("/channel/{username}/game/recheck")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recheckGame(@PathVariable String username) {
        apiService.recheckGame(username);
    }

    @PostMapping("/channel/{username}/bindings/{bindingId}/ccl-toggle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cclToggle(@PathVariable String username, @PathVariable String bindingId,
                           @RequestBody CclToggleRequest body) {
        apiService.cclToggle(username, bindingId, body.enabled());
    }

    @PostMapping("/channel/{username}/bindings/{bindingId}/ignored-toggle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ignoredToggle(@PathVariable String username, @PathVariable String bindingId,
                               @RequestBody IgnoredToggleRequest body) {
        apiService.ignoredToggle(username, bindingId, body.ignored());
    }

    @PostMapping("/channel/{username}/bindings/{bindingId}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBinding(@PathVariable String username, @PathVariable String bindingId) {
        apiService.deleteBinding(username, bindingId);
    }

    @GetMapping("/channel/{username}/games/search")
    public Object searchGames(@PathVariable String username, @RequestParam(defaultValue = "") String q) {
        return apiService.searchGames(username, q);
    }

    @PostMapping("/channel/{username}/bindings/{bindingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateBinding(@PathVariable String username, @PathVariable String bindingId,
                               @RequestBody UpdateBindingRequest body) {
        apiService.updateBinding(username, bindingId, body.twitchGameId(), body.twitchGameName(), body.ccls());
    }

    @PostMapping("/channel/{username}/bindings/{bindingId}/tws")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveTws(@PathVariable String username, @PathVariable String bindingId, @RequestBody SaveBody body) {
        apiService.saveTws(bindingId, body.tws());
    }

    @PostMapping("/channel/{username}/bindings/{bindingId}/tws/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetTws(@PathVariable String username, @PathVariable String bindingId) {
        apiService.resetTws(bindingId);
    }

    @PostMapping("/channel/{username}/bindings/{bindingId}/tw-enabled")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleTwEnabled(@PathVariable String username, @PathVariable String bindingId, @RequestBody TwEnabledBody body) {
        apiService.toggleTwEnabled(bindingId, body.enabled());
    }

    @PostMapping("/channel/{username}/settings/steam-personal-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveSteamToken(@PathVariable String username, @RequestBody SteamTokenRequest body) {
        apiService.saveSteamToken(username, body.token(), body.shared());
    }

    @PostMapping("/channel/{username}/settings/steam-personal-token/sharing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void steamTokenSharing(@PathVariable String username, @RequestBody SteamTokenSharingRequest body) {
        apiService.steamTokenSharing(username, body.shared());
    }

    @PostMapping("/channel/{username}/settings/steam-personal-token/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSteamToken(@PathVariable String username) {
        apiService.deleteSteamToken(username);
    }

    @PostMapping("/channel/{username}/steam/refresh-profile-cache")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refreshSteamProfileCache(@PathVariable String username) {
        apiService.refreshSteamProfileCache(username);
    }

    @GetMapping("/channel/{username}/minecraft")
    public LinkStateResponse minecraftStatus(@PathVariable String username) {
        return apiService.minecraftStatus(username);
    }

    @PostMapping("/channel/{username}/minecraft/enroll")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void minecraftEnroll(@PathVariable String username, @RequestBody java.util.Map<String, String> body) {
        apiService.minecraftEnroll(username, body.get("name"));
    }

    @PostMapping("/channel/{username}/minecraft/sync")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void minecraftSync(@PathVariable String username) {
        apiService.minecraftSync(username);
    }

    @PostMapping("/channel/{username}/minecraft/disconnect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void minecraftDisconnect(@PathVariable String username) {
        apiService.minecraftDisconnect(username);
    }

    @PostMapping("/channel/{username}/settings/ccl")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveCclSettings(@PathVariable String username, @RequestBody CclSettingsRequest body) {
        apiService.saveCclSettings(username, body.cclEnabled(), body.blockedCcls());
    }

    @PostMapping("/channel/{username}/settings/tws")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveTwSettings(@PathVariable String username, @RequestBody TwSettingsRequest body) {
        apiService.saveTwSettings(username, body.enabled(), body.blockedTws());
    }
}
