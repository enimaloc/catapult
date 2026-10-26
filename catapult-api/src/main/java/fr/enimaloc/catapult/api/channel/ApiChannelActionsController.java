package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.error.ApiProblem;
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
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.steam.SteamApiKeyEntry;
import fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import fr.enimaloc.catapult.getter.steam.SteamApiKeyRotator;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.steam.SteamApiKeyRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.account.AccountService;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.binding.BindingService;
import fr.enimaloc.catapult.service.binding.CustomBindingActivations;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.binding.RaidRestoreGameService;
import fr.enimaloc.catapult.service.binding.RaidRestoreGateService;
import fr.enimaloc.catapult.service.binding.SchedulerService;
import fr.enimaloc.catapult.service.connections.ProviderConnectionsDto;
import fr.enimaloc.catapult.service.connections.SteamProfileDiagnostics;
import fr.enimaloc.catapult.service.connections.SteamProfileDto;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.notification.TwitchatEventActionService;
import fr.enimaloc.catapult.service.notification.TwitchatEventDispatcher;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetSettingsService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

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

    private final UserAccountRepository userAccountRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final ApiUserResolver userResolver;
    private final BindingService bindingService;
    private final BotToggleService botToggleService;
    private final TwitchService twitchService;
    private final GameStateService gameStateService;
    private final AccountService accountService;
    private final SchedulerService schedulerService;
    private final TokenEncryptionService tokenEncryptionService;
    private final SteamApiKeyRepository steamApiKeyRepository;
    private final ChannelEventPublisher channelEventPublisher;
    private final TwitchatWidgetSettingsService twitchatWidgetSettingsService;
    private final TwitchatEventActionService twitchatEventActionService;
    private final TwitchatEventDispatcher twitchatEventDispatcher;
    private final SteamProfileDiagnostics steamDiagnostics;
    private final RaidRestoreGateService raidRestoreGate;
    private final CustomBindingActivations customBindingActivations;

    private final Optional<SteamApiKeyRotator> rotator;


    // ── Binding actions ───────────────────────────────────────────────────────

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/bindings/{bindingId}/ccl-enabled")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleCclEnabled(
            @PathVariable UUID userId,
            @PathVariable UUID bindingId,
            @RequestBody CclToggleRequest body) {

        UserAccount channelUser = userResolver.user(userId);
        bindingService.toggleCclEnabled(channelUser, bindingId, body.enabled());
        publishBinding(channelUser, bindingId);
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/bindings/{bindingId}/ignored")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleIgnored(
            @PathVariable UUID userId,
            @PathVariable UUID bindingId,
            @RequestBody IgnoredToggleRequest body) {

        UserAccount channelUser = userResolver.user(userId);
        bindingService.toggleIgnored(channelUser, bindingId, body.ignored());
        publishBinding(channelUser, bindingId);
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @DeleteMapping("/bindings/{bindingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBinding(
            @PathVariable UUID userId,
            @PathVariable UUID bindingId) {

        UserAccount channelUser = userResolver.user(userId);
        bindingService.deleteBinding(channelUser, bindingId);
        channelEventPublisher.bindingDeleted(channelUser.getId(), bindingId);
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PatchMapping("/bindings/{bindingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateBinding(
            @PathVariable UUID userId,
            @PathVariable UUID bindingId,
            @RequestBody UpdateBindingRequest body) {

        UserAccount channelUser = userResolver.user(userId);
        Set<String> ccls = body.ccls() != null ? body.ccls() : Set.of();
        bindingService.updateBinding(channelUser, bindingId, body.twitchGameId(), body.twitchGameName(), ccls, false);
        publishBinding(channelUser, bindingId);
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

        UserAccount user = userResolver.user(userId);
        if (user.getStatus() != UserAccount.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account is not active");
        }
        schedulerService.triggerManualCheck(user);
        // The dashboards are told what is detected now, changed or not: the check's own event only
        // goes out on a change, and catapult-web no longer publishes one itself.
        channelEventPublisher.gameDetected(user.getId(), gameStateService.getLastKnownGame(user).orElse(null));
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/settings/ccl")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveCclSettings(
            @PathVariable UUID userId,
            @RequestBody CclSettingsRequest body) {

        UserAccount channelUser = userResolver.user(userId);
        UserSettings settings = saveSettings(channelUser, s -> {
            s.setCclFeatureEnabled(body.cclEnabled());
            replace(s.getBlockedCcls(), body.blockedCcls());
        });
        publishSettings(channelUser, settings);
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/settings/tws")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveTwSettings(
            @PathVariable UUID userId,
            @RequestBody TwSettingsRequest body) {

        UserAccount channelUser = userResolver.user(userId);
        UserSettings settings = saveSettings(channelUser, s -> {
            s.setTwFeatureEnabled(body.enabled());
            replace(s.getBlockedTws(), body.blockedTws());
        });
        publishSettings(channelUser, settings);
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/settings/no-game")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveNoGameSettings(
            @PathVariable UUID userId,
            @RequestBody NoGameSettingsRequest body) {

        UserAccount channelUser = userResolver.user(userId);
        UserSettings settings = saveSettings(channelUser, s -> {
            s.setNoGameTwitchGameId(body.twitchGameId());
            s.setNoGameTwitchGameName(body.twitchGameName());
            replace(s.getNoGameCcls(), body.ccls());
            s.setApplyDefaultOnStreamStart(body.applyOnStreamStart());
            s.setApplyDefaultOnNoGame(body.applyOnNoGame());
            s.setApplyDefaultOnStreamEnd(body.applyOnStreamEnd());
        });
        // Nothing detected right now: the new default category applies immediately, unless a custom binding holds
        // the channel
        if (gameStateService.getLastKnownGame(channelUser).isEmpty()
                && customBindingActivations.active(channelUser).isEmpty()) {
            twitchService.resetToDefault(channelUser);
        }
        publishSettings(channelUser, settings);
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/settings/incomplete-fallback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveIncompleteFallbackSettings(
            @PathVariable UUID userId,
            @RequestBody IncompleteFallbackRequest body) {

        UserAccount channelUser = userResolver.user(userId);
        UserSettings settings = saveSettings(channelUser, s -> {
            s.setIncompleteFallbackTwitchGameId(body.twitchGameId());
            s.setIncompleteFallbackTwitchGameName(body.twitchGameName());
            replace(s.getIncompleteFallbackCcls(), body.ccls());
        });
        publishSettings(channelUser, settings);
    }

    /** 0 or null turns it off; refused (403) while the channel isn't in the {@value RaidRestoreGateService#EXPERIMENT_KEY} experiment. */
    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/settings/raid")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveRaidSettings(
            @PathVariable UUID userId,
            @RequestBody RaidSettingsRequest body) {

        UserAccount channelUser = userResolver.user(userId);
        if (!raidRestoreGate.isAvailableFor(channelUser)) {
            throw new ApiProblem(ErrorCode.FEATURE_DISABLED, "Raid settings not available for this channel");
        }
        Integer minutes = body.restoreGameMinutes();
        if (minutes != null && minutes != 0
                && (minutes < RaidRestoreGameService.MIN_MINUTES || minutes > RaidRestoreGameService.MAX_MINUTES)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "restoreGameMinutes must be 1..120, or 0/null for off");
        }
        UserSettings settings = saveSettings(channelUser,
                s -> s.setRaidRestoreGameMinutes(minutes == null || minutes == 0 ? null : minutes));
        publishSettings(channelUser, settings);
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PutMapping("/settings/steam-personal-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveSteamPersonalToken(
            @PathVariable UUID userId,
            @RequestBody SteamTokenRequest body) {

        UserAccount channelUser = userResolver.user(userId);
        if (body.token() == null || body.token().isBlank()) return;
        String trimmed = body.token().trim();
        channelUser.setSteamPersonalToken(tokenEncryptionService.encrypt(trimmed));
        channelUser.setSteamTokenShared(body.shared());
        userAccountRepository.save(channelUser);
        syncTokenToPool(channelUser, trimmed, body.shared());
        channelEventPublisher.steamProfileChanged(channelUser.getId(), buildSteamProfile(channelUser));
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PutMapping("/settings/steam-personal-token/sharing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateSteamTokenSharing(
            @PathVariable UUID userId,
            @RequestBody SteamTokenSharingRequest body) {

        UserAccount channelUser = userResolver.user(userId);
        if (channelUser.getSteamPersonalToken() == null) return;
        channelUser.setSteamTokenShared(body.shared());
        userAccountRepository.save(channelUser);
        String decryptedToken = tokenEncryptionService.decrypt(channelUser.getSteamPersonalToken());
        syncTokenToPool(channelUser, decryptedToken, body.shared());
        channelEventPublisher.steamProfileChanged(channelUser.getId(), buildSteamProfile(channelUser));
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @Transactional
    @DeleteMapping("/settings/steam-personal-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSteamPersonalToken(
            @PathVariable UUID userId) {

        UserAccount channelUser = userResolver.user(userId);
        channelUser.setSteamPersonalToken(null);
        channelUser.setSteamTokenShared(false);
        userAccountRepository.save(channelUser);
        steamApiKeyRepository.deleteByOwner(channelUser);
        rotator.ifPresent(SteamApiKeyRotator::refreshKeys);
        channelEventPublisher.steamProfileChanged(channelUser.getId(), buildSteamProfile(channelUser));
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PostMapping("/deletion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(
            @PathVariable UUID userId,
            @RequestBody DeleteAccountRequest body) {

        UserAccount channelUser = userResolver.user(userId);
        if (channelUser.getTwitchUsername().equalsIgnoreCase(body.confirmUsername())) {
            accountService.initiateAccountDeletion(channelUser);
        }
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
        accountService.disconnectProvider(channelUser, disconnected);
        channelEventPublisher.connectionChanged(channelUser.getId(),
                new ProviderConnectionsDto(disconnected.name(), false, null));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SteamProfileDto buildSteamProfile(UserAccount channelUser) {
        return steamDiagnostics.diagnose(channelUser);
    }

    /** Loads the channel's settings (new ones when it has none), applies {@code changes} and saves them. */
    private UserSettings saveSettings(UserAccount channel, Consumer<UserSettings> changes) {
        UserSettings settings = userSettingsRepository.findById(channel.getId()).orElseGet(() -> {
            UserSettings created = new UserSettings();
            created.setUser(channel);
            return created;
        });
        changes.accept(settings);
        userSettingsRepository.save(settings);
        return settings;
    }

    /** Replaces {@code target}'s content with {@code values} (none when null). */
    private static void replace(Set<String> target, Set<String> values) {
        target.clear();
        if (values != null) target.addAll(values);
    }

    private void publishSettings(UserAccount channel, UserSettings settings) {
        channelEventPublisher.settingsUpdated(channel.getId(), settings);
    }

    /** Tells the channel's dashboards about a binding's new state (nothing if it's gone). */
    private void publishBinding(UserAccount channel, UUID bindingId) {
        bindingService.findBinding(channel, bindingId)
                .ifPresent(binding -> channelEventPublisher.bindingUpserted(channel.getId(), binding));
    }

    private static TwitchatSettingsResponse twitchatResponse(UserAccount channel, TwitchatWidgetSettings settings) {
        return new TwitchatSettingsResponse(settings.isEnabled(), settings.getObsHost(), settings.getObsPort(),
                settings.getObsPasswordEncrypted() != null, channel.getWidgetToken().toString());
    }

    private void syncTokenToPool(UserAccount user, String plainToken, boolean shared) {
        steamApiKeyRepository.deleteByOwner(user);
        if (!steamApiKeyRepository.existsById(plainToken)) {
            SteamApiKeyEntry entry = new SteamApiKeyEntry(plainToken);
            entry.setOwner(user);
            entry.setExclusive(!shared);
            steamApiKeyRepository.save(entry);
        }
        rotator.ifPresent(SteamApiKeyRotator::refreshKeys);
    }

}
