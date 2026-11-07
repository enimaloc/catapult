package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.channel.CclSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.CclToggleRequest;
import fr.enimaloc.catapult.common.dto.channel.DeleteAccountRequest;
import fr.enimaloc.catapult.common.dto.channel.IgnoredToggleRequest;
import fr.enimaloc.catapult.common.dto.channel.IncompleteFallbackRequest;
import fr.enimaloc.catapult.common.dto.channel.NoGameSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.ObsSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.RaidSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.TwitchatBranchRequest;
import fr.enimaloc.catapult.common.dto.channel.SteamTokenRequest;
import fr.enimaloc.catapult.common.dto.channel.SteamTokenSharingRequest;
import fr.enimaloc.catapult.common.dto.channel.TwSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.UpdateBindingRequest;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatSettingsBody;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatSettingsResponse;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import fr.enimaloc.catapult.service.account.AccountService;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.account.ChannelAccountActions;
import fr.enimaloc.catapult.service.account.ChannelSettingsService;
import fr.enimaloc.catapult.service.binding.ChannelDashboardActions;
import fr.enimaloc.catapult.service.binding.RaidRestoreGateService;
import fr.enimaloc.catapult.service.notification.TwitchatEventActionService;
import fr.enimaloc.catapult.service.notification.TwitchatEventDispatcher;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetSettingsService;
import fr.enimaloc.catapult.service.steam.SteamPersonalTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a channel's dashboard changes: its bindings and settings ({@code @authz.canAccessChannel}, the owner or one of
 * its moderators) and its owner-only settings, Twitchat and account actions ({@code @authz.ownsChannel}). The raid
 * settings' experiment gate stays in the body. The rule sits on each handler, not on the class: a class-level one
 * would also guard {@link #onInvalidTwitchatPayloads}, which has no {@code userId}, and turn every refused Twitchat
 * payload into a 404.
 */
@Slf4j
@RestController
@RequestMapping(ApiPaths.USER)
@RequiredArgsConstructor
public class ApiChannelActionsController {

    private final ApiUserResolver userResolver;
    private final ChannelDashboardActions dashboardActions;
    private final BotToggleService botToggleService;
    private final ChannelSettingsService settingsService;
    private final SteamPersonalTokenService steamTokenService;
    private final AccountService accountService;
    private final ChannelAccountActions accountActions;
    private final TwitchatWidgetSettingsService twitchatWidgetSettingsService;
    private final TwitchatEventActionService twitchatEventActionService;
    private final TwitchatEventDispatcher twitchatEventDispatcher;


    // ── Binding actions ───────────────────────────────────────────────────────

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/bindings/{bindingId}/ccl-enabled")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleCclEnabled(
            @PathVariable UUID userId,
            @PathVariable UUID bindingId,
            @RequestBody CclToggleRequest body) {

        dashboardActions.toggleCcl(userResolver.user(userId), bindingId, body.enabled());
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/bindings/{bindingId}/ignored")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleIgnored(
            @PathVariable UUID userId,
            @PathVariable UUID bindingId,
            @RequestBody IgnoredToggleRequest body) {

        dashboardActions.toggleIgnored(userResolver.user(userId), bindingId, body.ignored());
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @DeleteMapping("/bindings/{bindingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBinding(
            @PathVariable UUID userId,
            @PathVariable UUID bindingId) {

        dashboardActions.delete(userResolver.user(userId), bindingId);
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PatchMapping("/bindings/{bindingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateBinding(
            @PathVariable UUID userId,
            @PathVariable UUID bindingId,
            @RequestBody UpdateBindingRequest body) {

        dashboardActions.update(userResolver.user(userId), bindingId, body);
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PostMapping("/settings/bot/toggle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleBot(
            @PathVariable UUID userId) {

        UserAccount user = userResolver.user(userId);
        botToggleService.setBotEnabled(user, !user.isBotEnabled());
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @GetMapping("/settings/twitchat")
    public TwitchatSettingsResponse getTwitchatSettings(
            @PathVariable UUID userId) {

        UserAccount channelUser = userResolver.user(userId);
        var settings = twitchatWidgetSettingsService.getOrCreate(channelUser);
        return twitchatResponse(channelUser, settings);
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PutMapping("/settings/twitchat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveTwitchatSettings(
            @PathVariable UUID userId,
            @RequestBody TwitchatSettingsBody body) {

        UserAccount channelUser = userResolver.user(userId);
        twitchatWidgetSettingsService.updateSettings(channelUser, body.enabled(), body.obsHost(), body.obsPort(), body.obsPassword());
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PutMapping("/settings/obs")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveObsSettings(
            @PathVariable UUID userId,
            @RequestBody ObsSettingsRequest body) {

        UserAccount channelUser = userResolver.user(userId);
        twitchatWidgetSettingsService.updateSettings(channelUser, body.enabled(), body.host(), body.port(), body.password());
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PutMapping("/settings/twitchat/branch")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveTwitchatBranch(
            @PathVariable UUID userId,
            @RequestBody TwitchatBranchRequest body) {

        UserAccount channelUser = userResolver.user(userId);
        try {
            twitchatWidgetSettingsService.updateBranch(channelUser, body.branch());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PostMapping("/settings/twitchat/regenerate")
    public TwitchatSettingsResponse regenerateTwitchatToken(
            @PathVariable UUID userId) {

        UserAccount channelUser = userResolver.user(userId);
        var settings = twitchatWidgetSettingsService.regenerateToken(channelUser);
        return twitchatResponse(channelUser, settings);
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @GetMapping("/twitchat-events")
    public Map<String, List<TwitchatEventActionDto>> twitchatEvents(
            @PathVariable UUID userId) {
        UserAccount channelUser = userResolver.user(userId);
        Map<String, List<TwitchatEventActionDto>> result = new LinkedHashMap<>();
        twitchatEventActionService.all(channelUser).forEach((type, actions) -> result.put(type.name(), actions.stream()
                .map(a -> new TwitchatEventActionDto(a.getAction(), a.getMode().name(), a.getDataJson(), a.isEnabled()))
                .toList()));
        return result;
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PutMapping("/twitchat-events/{eventType}/actions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveTwitchatEvent(
            @PathVariable UUID userId, @PathVariable String eventType,
            @RequestBody List<TwitchatEventActionDto> body) {
        UserAccount channelUser = userResolver.user(userId);
        twitchatEventActionService.replace(channelUser, parseEventType(eventType), body);
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PostMapping("/twitchat-events/{eventType}/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void testTwitchatEvent(
            @PathVariable UUID userId, @PathVariable String eventType,
            @RequestBody List<TwitchatEventActionDto> body) {
        UserAccount channelUser = userResolver.user(userId);
        twitchatEventDispatcher.sendTest(channelUser, parseEventType(eventType), body);
    }

    private static TwitchatNotificationEventType parseEventType(String raw) {
        try {
            return TwitchatNotificationEventType.valueOf(raw);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown event type: " + raw);
        }
    }

    // ── Game detection ───────────────────────────────────────────────────────

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PostMapping("/game/recheck")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recheckGame(
            @PathVariable UUID userId) {

        dashboardActions.recheckGame(userResolver.user(userId));
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/settings/ccl")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveCclSettings(
            @PathVariable UUID userId,
            @RequestBody CclSettingsRequest body) {

        settingsService.saveCcl(userResolver.user(userId), body);
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/settings/tws")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveTwSettings(
            @PathVariable UUID userId,
            @RequestBody TwSettingsRequest body) {

        settingsService.saveTws(userResolver.user(userId), body);
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/settings/no-game")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveNoGameSettings(
            @PathVariable UUID userId,
            @RequestBody NoGameSettingsRequest body) {

        settingsService.saveNoGame(userResolver.user(userId), body);
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/settings/incomplete-fallback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveIncompleteFallbackSettings(
            @PathVariable UUID userId,
            @RequestBody IncompleteFallbackRequest body) {

        settingsService.saveIncompleteFallback(userResolver.user(userId), body);
    }

    /** 0 or null turns it off; refused (403) while the channel isn't in the {@value RaidRestoreGateService#EXPERIMENT_KEY} experiment. */
    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/settings/raid")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveRaidSettings(
            @PathVariable UUID userId,
            @RequestBody RaidSettingsRequest body) {

        settingsService.saveRaid(userResolver.user(userId), body);
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PutMapping("/settings/steam-personal-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveSteamPersonalToken(
            @PathVariable UUID userId,
            @RequestBody SteamTokenRequest body) {

        steamTokenService.save(userResolver.user(userId), body);
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PutMapping("/settings/steam-personal-token/sharing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateSteamTokenSharing(
            @PathVariable UUID userId,
            @RequestBody SteamTokenSharingRequest body) {

        steamTokenService.updateSharing(userResolver.user(userId), body.shared());
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @DeleteMapping("/settings/steam-personal-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSteamPersonalToken(
            @PathVariable UUID userId) {

        steamTokenService.delete(userResolver.user(userId));
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PostMapping("/deletion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(
            @PathVariable UUID userId,
            @RequestBody DeleteAccountRequest body) {

        accountActions.requestDeletion(userResolver.user(userId), body.confirmUsername());
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @DeleteMapping("/deletion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelDeletion(
            @PathVariable UUID userId) {

        UserAccount channelUser = userResolver.user(userId);
        accountService.cancelAccountDeletion(channelUser);
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @DeleteMapping("/oauth-tokens/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnectProvider(
            @PathVariable UUID userId,
            @PathVariable String provider) {

        UserAccount channelUser = userResolver.user(userId);
        OAuthToken.Provider disconnected = OAuthToken.Provider.valueOf(provider.toUpperCase());
        accountActions.disconnect(channelUser, disconnected);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static TwitchatSettingsResponse twitchatResponse(UserAccount channel, TwitchatWidgetSettings settings) {
        return new TwitchatSettingsResponse(settings.isEnabled(), settings.getObsHost(), settings.getObsPort(),
                settings.getObsPasswordEncrypted() != null, channel.getWidgetToken().toString());
    }
}
