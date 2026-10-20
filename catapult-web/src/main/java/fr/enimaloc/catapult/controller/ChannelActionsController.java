package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.CclSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.CclToggleRequest;
import fr.enimaloc.catapult.common.dto.channel.DeleteAccountRequest;
import fr.enimaloc.catapult.common.dto.channel.IgnoredToggleRequest;
import fr.enimaloc.catapult.common.dto.channel.IncompleteFallbackRequest;
import fr.enimaloc.catapult.common.dto.channel.NoGameSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.ObsSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.TwitchatBranchRequest;
import fr.enimaloc.catapult.common.dto.channel.ProposeRequest;
import fr.enimaloc.catapult.common.dto.channel.SaveBody;
import fr.enimaloc.catapult.common.dto.channel.SearchResponse;
import fr.enimaloc.catapult.common.dto.channel.SteamTokenRequest;
import fr.enimaloc.catapult.common.dto.channel.SteamTokenSharingRequest;
import fr.enimaloc.catapult.common.dto.channel.TwEnabledBody;
import fr.enimaloc.catapult.common.dto.channel.TwSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.UpdateBindingRequest;
import fr.enimaloc.catapult.common.dto.channel.ValidateRequest;
import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browser-facing mutation endpoints for the channel dashboard page. These exist because
 * the session JWT ApiClient authenticates with never reaches the browser (see ApiClient's
 * HttpSession lookup) — the browser can only call back into catapult-web itself, which
 * then forwards to catapult-api with the server-held JWT via ApiService. Each answers catapult-api's
 * outcome (see {@link ApiStatus}): 204 once done, its refusal or failure as is, 502 when unreachable,
 * so that the page can tell and put a switch back.
 */
@RestController
@RequiredArgsConstructor
public class ChannelActionsController {
    private final ApiService apiService;

    @PostMapping("/channel/{username}/settings/bot")
    public ResponseEntity<Void> toggleBot(@PathVariable String username) {
        return ApiStatus.answer(apiService.toggleBot(username));
    }

    /**
     * Rechecks; the game now detected reaches the channel's streams as catapult-api's own event, on
     * every instance (never published from here: any member could then tell a channel's dashboards
     * anything, and only this instance's would hear it).
     */
    @PostMapping("/channel/{username}/game/recheck")
    public ResponseEntity<Void> recheckGame(@PathVariable String username) {
        return ApiStatus.answer(apiService.recheckGame(username));
    }

    @PostMapping("/channel/{username}/bindings/{bindingId}/ccl-toggle")
    public ResponseEntity<Void> cclToggle(@PathVariable String username, @PathVariable String bindingId,
                           @RequestBody CclToggleRequest body) {
        return ApiStatus.answer(apiService.cclToggle(username, bindingId, body.enabled()));
    }

    @PostMapping("/channel/{username}/bindings/{bindingId}/ignored-toggle")
    public ResponseEntity<Void> ignoredToggle(@PathVariable String username, @PathVariable String bindingId,
                               @RequestBody IgnoredToggleRequest body) {
        return ApiStatus.answer(apiService.ignoredToggle(username, bindingId, body.ignored()));
    }

    @PostMapping("/channel/{username}/bindings/{bindingId}/delete")
    public ResponseEntity<Void> deleteBinding(@PathVariable String username, @PathVariable String bindingId) {
        return ApiStatus.answer(apiService.deleteBinding(username, bindingId));
    }

    @GetMapping("/channel/{username}/games/search")
    public Object searchGames(@PathVariable String username, @RequestParam(defaultValue = "") String q) {
        return apiService.searchGames(username, q);
    }

    @PostMapping("/channel/{username}/bindings/{bindingId}")
    public ResponseEntity<Void> updateBinding(@PathVariable String username, @PathVariable String bindingId,
                               @RequestBody UpdateBindingRequest body) {
        return ApiStatus.answer(apiService.updateBinding(username, bindingId, body.twitchGameId(), body.twitchGameName(), body.ccls()));
    }

    // Unlike the sibling binding endpoints above, the upstream ApiChannelTwController on
    // catapult-api is binding-scoped, not username-scoped, so these routes don't take
    // {username}.
    @PostMapping("/channel/bindings/{bindingId}/tws")
    public ResponseEntity<Void> saveTws(@PathVariable String bindingId, @RequestBody SaveBody body) {
        return ApiStatus.answer(apiService.saveTws(bindingId, body.tws()));
    }

    @PostMapping("/channel/bindings/{bindingId}/tws/reset")
    public ResponseEntity<Void> resetTws(@PathVariable String bindingId) {
        return ApiStatus.answer(apiService.resetTws(bindingId));
    }

    @PostMapping("/channel/bindings/{bindingId}/tw-enabled")
    public ResponseEntity<Void> toggleTwEnabled(@PathVariable String bindingId, @RequestBody TwEnabledBody body) {
        return ApiStatus.answer(apiService.toggleTwEnabled(bindingId, body.enabled()));
    }

    /** Answered with catapult-api's status: the account's deletion is the one action the page must not guess at. */
    @PostMapping("/channel/{username}/settings/delete-account")
    public ResponseEntity<Void> deleteAccount(@PathVariable String username, @RequestBody DeleteAccountRequest body) {
        return ApiStatus.answer(apiService.deleteAccount(username, body.confirmUsername()));
    }

    @PostMapping("/channel/{username}/settings/cancel-deletion")
    public ResponseEntity<Void> cancelDeletion(@PathVariable String username) {
        return ApiStatus.answer(apiService.cancelAccountDeletion(username));
    }

    @PostMapping("/channel/{username}/settings/steam-personal-token")
    public ResponseEntity<Void> saveSteamToken(@PathVariable String username, @RequestBody SteamTokenRequest body) {
        return ApiStatus.answer(apiService.saveSteamToken(username, body.token(), body.shared()));
    }

    @PostMapping("/channel/{username}/settings/steam-personal-token/sharing")
    public ResponseEntity<Void> steamTokenSharing(@PathVariable String username, @RequestBody SteamTokenSharingRequest body) {
        return ApiStatus.answer(apiService.steamTokenSharing(username, body.shared()));
    }

    @PostMapping("/channel/{username}/settings/steam-personal-token/delete")
    public ResponseEntity<Void> deleteSteamToken(@PathVariable String username) {
        return ApiStatus.answer(apiService.deleteSteamToken(username));
    }

    @PostMapping("/channel/{username}/steam/refresh-profile-cache")
    public ResponseEntity<Void> refreshSteamProfileCache(@PathVariable String username) {
        return ApiStatus.answer(apiService.refreshSteamProfileCache(username));
    }

    @GetMapping("/channel/{username}/minecraft")
    public LinkStateResponse minecraftStatus(@PathVariable String username) {
        return apiService.minecraftStatus(username);
    }

    @PostMapping("/channel/{username}/minecraft/enroll")
    public ResponseEntity<Void> minecraftEnroll(@PathVariable String username, @RequestBody java.util.Map<String, String> body) {
        return ApiStatus.answer(apiService.minecraftEnroll(username, body.get("name")));
    }

    @PostMapping("/channel/{username}/minecraft/sync")
    public ResponseEntity<Void> minecraftSync(@PathVariable String username) {
        return ApiStatus.answer(apiService.minecraftSync(username));
    }

    @PostMapping("/channel/{username}/minecraft/disconnect")
    public ResponseEntity<Void> minecraftDisconnect(@PathVariable String username) {
        return ApiStatus.answer(apiService.minecraftDisconnect(username));
    }

    @PostMapping("/channel/{username}/settings/obs")
    public ResponseEntity<Void> saveObsSettings(@PathVariable String username, @RequestBody ObsSettingsRequest body) {
        return ApiStatus.answer(apiService.saveObsSettings(username, body.enabled(), body.host(), body.port(), body.password()));
    }

    @PostMapping("/channel/{username}/settings/twitchat/branch")
    public ResponseEntity<Void> saveTwitchatBranch(@PathVariable String username, @RequestBody TwitchatBranchRequest body) {
        return ApiStatus.answer(apiService.saveTwitchatBranch(username, body.branch()));
    }

    /** Replaces the OBS widget link: 204 (the page re-reads the new link), else catapult-api's status, 502 when unreachable. */
    @PostMapping("/channel/{username}/settings/twitchat/regenerate")
    public ResponseEntity<Void> regenerateTwitchatWidget(@PathVariable String username) {
        return ApiStatus.answer(apiService.regenerateTwitchatWidget(username).status());
    }

    @PostMapping("/channel/{username}/settings/ccl")
    public ResponseEntity<Void> saveCclSettings(@PathVariable String username, @RequestBody CclSettingsRequest body) {
        return ApiStatus.answer(apiService.saveCclSettings(username, body.cclEnabled(), body.blockedCcls()));
    }

    @PostMapping("/channel/{username}/settings/tws")
    public ResponseEntity<Void> saveTwSettings(@PathVariable String username, @RequestBody TwSettingsRequest body) {
        return ApiStatus.answer(apiService.saveTwSettings(username, body.enabled(), body.blockedTws()));
    }

    @PostMapping("/channel/{username}/settings/no-game")
    public ResponseEntity<Void> saveNoGameSettings(@PathVariable String username, @RequestBody NoGameSettingsRequest body) {
        return ApiStatus.answer(apiService.saveNoGameSettings(username, body.twitchGameId(), body.twitchGameName(), body.ccls(),
                body.applyOnStreamStart(), body.applyOnNoGame(), body.applyOnStreamEnd()));
    }

    @PostMapping("/channel/{username}/settings/incomplete-fallback")
    public ResponseEntity<Void> saveIncompleteFallbackSettings(@PathVariable String username, @RequestBody IncompleteFallbackRequest body) {
        return ApiStatus.answer(apiService.saveIncompleteFallbackSettings(username, body.twitchGameId(), body.twitchGameName(), body.ccls()));
    }

    // Unlike the sibling channel endpoints above, the upstream ApiChannelDtddMappingController
    // on catapult-api is globally scoped, not username-scoped, so these routes don't take
    // {username}.
    @GetMapping("/channel/dtdd-mapping/search")
    public SearchResponse dtddSearch(@RequestParam(defaultValue = "") String q) {
        return apiService.dtddSearch(q);
    }

    @PostMapping("/channel/dtdd-mapping/validate")
    public ResponseEntity<Void> dtddValidate(@RequestBody ValidateRequest body) {
        return ApiStatus.answer(apiService.dtddValidate(body.igdbId()));
    }

    @PostMapping("/channel/dtdd-mapping/propose")
    public ResponseEntity<Void> dtddPropose(@RequestBody ProposeRequest body) {
        return ApiStatus.answer(apiService.dtddPropose(body.igdbId(), body.dtddId(), body.reason()));
    }
}
