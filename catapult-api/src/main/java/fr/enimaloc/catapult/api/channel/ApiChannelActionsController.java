package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.channel.CclSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.CclToggleRequest;
import fr.enimaloc.catapult.common.dto.channel.DeleteAccountRequest;
import fr.enimaloc.catapult.common.dto.channel.DisconnectRequest;
import fr.enimaloc.catapult.common.dto.channel.IgnoredToggleRequest;
import fr.enimaloc.catapult.common.dto.channel.IncompleteFallbackRequest;
import fr.enimaloc.catapult.common.dto.channel.NoGameSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.SteamTokenRequest;
import fr.enimaloc.catapult.common.dto.channel.SteamTokenSharingRequest;
import fr.enimaloc.catapult.common.dto.channel.TwSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.UpdateBindingRequest;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatActivePresetBody;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatPresetBody;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatPresetResponse;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatPresetTestBody;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatPresetUpdateBody;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatSettingsBody;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatSettingsResponse;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.steam.SteamApiKeyEntry;
import fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType;
import fr.enimaloc.catapult.domain.twitchat.TwitchatPayloadPreset;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import fr.enimaloc.catapult.getter.steam.SteamApiKeyRotator;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.steam.SteamApiKeyRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.account.AccountService;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.binding.BindingDto;
import fr.enimaloc.catapult.service.binding.BindingService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.binding.SchedulerService;
import fr.enimaloc.catapult.service.connections.ProviderConnectionsDto;
import fr.enimaloc.catapult.service.connections.SteamProfileDiagnostics;
import fr.enimaloc.catapult.service.connections.SteamProfileDto;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.notification.TwitchatNotifier;
import fr.enimaloc.catapult.service.notification.TwitchatPayloadPresetService;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetSettingsService;
import fr.enimaloc.catapult.service.settings.UserSettingsDto;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

@Slf4j
@RestController
@RequestMapping("/api/channels/{username}")
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
    private final TwitchatPayloadPresetService twitchatPayloadPresetService;
    private final TwitchatNotifier twitchatNotifier;
    private final SteamProfileDiagnostics steamDiagnostics;

    @Autowired(required = false)
    private SteamApiKeyRotator rotator;


    // ── Binding actions ───────────────────────────────────────────────────────

    @PostMapping("/bindings/{id}/ccl-toggle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleCclEnabled(
            @PathVariable String username,
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CclToggleRequest body) {

        UserAccount channelUser = userResolver.accessibleChannel(username, jwt);
        bindingService.toggleCclEnabled(channelUser, id, body.enabled());
        publishBinding(channelUser, id);
    }

    @PostMapping("/bindings/{id}/ignored-toggle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleIgnored(
            @PathVariable String username,
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody IgnoredToggleRequest body) {

        UserAccount channelUser = userResolver.accessibleChannel(username, jwt);
        bindingService.toggleIgnored(channelUser, id, body.ignored());
        publishBinding(channelUser, id);
    }

    @PostMapping("/bindings/{id}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBinding(
            @PathVariable String username,
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {

        UserAccount channelUser = userResolver.accessibleChannel(username, jwt);
        bindingService.deleteBinding(channelUser, id);
        channelEventPublisher.bindingDeleted(channelUser.getId(), id);
    }

    @PostMapping("/bindings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateBinding(
            @PathVariable String username,
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody UpdateBindingRequest body) {

        UserAccount channelUser = userResolver.accessibleChannel(username, jwt);
        Set<String> ccls = body.ccls() != null ? body.ccls() : Set.of();
        bindingService.updateBinding(channelUser, id, body.twitchGameId(), body.twitchGameName(), ccls, false);
        publishBinding(channelUser, id);
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    @PostMapping("/settings/bot")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleBot(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt) {

        UserAccount user = userResolver.ownChannel(username, jwt);
        botToggleService.setBotEnabled(user, !user.isBotEnabled());
    }

    @GetMapping("/settings/twitchat")
    public TwitchatSettingsResponse getTwitchatSettings(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt) {

        UserAccount channelUser = userResolver.ownChannel(username, jwt);
        var settings = twitchatWidgetSettingsService.getOrCreate(channelUser);
        return twitchatResponse(channelUser, settings);
    }

    @PostMapping("/settings/twitchat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveTwitchatSettings(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody TwitchatSettingsBody body) {

        UserAccount channelUser = userResolver.ownChannel(username, jwt);
        twitchatWidgetSettingsService.updateSettings(channelUser, body.enabled(), body.obsHost(), body.obsPort(), body.obsPassword());
    }

    @PostMapping("/settings/twitchat/regenerate")
    public TwitchatSettingsResponse regenerateTwitchatToken(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt) {

        UserAccount channelUser = userResolver.ownChannel(username, jwt);
        var settings = twitchatWidgetSettingsService.regenerateToken(channelUser);
        return twitchatResponse(channelUser, settings);
    }

    @GetMapping("/twitchat/presets")
    public List<TwitchatPresetResponse> listTwitchatPresets(
            @PathVariable String username, @AuthenticationPrincipal Jwt jwt) {
        UserAccount channelUser = userResolver.ownChannel(username, jwt);
        return twitchatPayloadPresetService.listPresets(channelUser).stream()
                .map(ApiChannelActionsController::toPresetResponse)
                .toList();
    }

    @PostMapping("/twitchat/presets")
    @ResponseStatus(HttpStatus.CREATED)
    public TwitchatPresetResponse createTwitchatPreset(
            @PathVariable String username, @AuthenticationPrincipal Jwt jwt,
            @RequestBody TwitchatPresetBody body) {
        UserAccount channelUser = userResolver.ownChannel(username, jwt);
        TwitchatPayloadPreset preset = twitchatPayloadPresetService.createPreset(channelUser,
                parseEventType(body.eventType()), body.name(), body.payloadJson());
        return toPresetResponse(preset);
    }

    @PutMapping("/twitchat/presets/{id}")
    public TwitchatPresetResponse updateTwitchatPreset(
            @PathVariable String username, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            @RequestBody TwitchatPresetUpdateBody body) {
        UserAccount channelUser = userResolver.ownChannel(username, jwt);
        TwitchatPayloadPreset preset = twitchatPayloadPresetService.updatePreset(channelUser, id,
                body.name(), body.payloadJson());
        return toPresetResponse(preset);
    }

    @DeleteMapping("/twitchat/presets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTwitchatPreset(
            @PathVariable String username, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UserAccount channelUser = userResolver.ownChannel(username, jwt);
        twitchatPayloadPresetService.deletePreset(channelUser, id);
    }

    @GetMapping("/twitchat/active-presets")
    public Map<String, String> getActiveTwitchatPresets(
            @PathVariable String username, @AuthenticationPrincipal Jwt jwt) {
        UserAccount channelUser = userResolver.ownChannel(username, jwt);
        Map<String, String> result = new LinkedHashMap<>();
        twitchatPayloadPresetService.getActivePresets(channelUser)
                .forEach((eventType, presetId) -> result.put(eventType.name(), presetId.toString()));
        return result;
    }

    @PutMapping("/twitchat/active-presets/{eventType}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setActiveTwitchatPreset(
            @PathVariable String username, @PathVariable String eventType, @AuthenticationPrincipal Jwt jwt,
            @RequestBody TwitchatActivePresetBody body) {
        UserAccount channelUser = userResolver.ownChannel(username, jwt);
        UUID presetId = body.presetId() == null || body.presetId().isBlank() ? null : UUID.fromString(body.presetId());
        twitchatPayloadPresetService.setActivePreset(channelUser, parseEventType(eventType), presetId);
    }

    @PostMapping("/twitchat/presets/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void testTwitchatPreset(
            @PathVariable String username, @AuthenticationPrincipal Jwt jwt,
            @RequestBody TwitchatPresetTestBody body) {
        UserAccount channelUser = userResolver.ownChannel(username, jwt);
        twitchatNotifier.sendTestNotification(channelUser, parseEventType(body.eventType()), body.payloadJson());
    }

    private static TwitchatNotificationEventType parseEventType(String raw) {
        try {
            return TwitchatNotificationEventType.valueOf(raw);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown event type: " + raw);
        }
    }

    private static TwitchatPresetResponse toPresetResponse(TwitchatPayloadPreset preset) {
        return new TwitchatPresetResponse(preset.getId().toString(), preset.getEventType().name(),
                preset.getName(), preset.getPayloadJson());
    }

    // ── Game detection ───────────────────────────────────────────────────────

    @PostMapping("/game/recheck")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recheckGame(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt) {

        UserAccount user = userResolver.ownChannel(username, jwt);
        if (user.getStatus() != UserAccount.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account is not active");
        }
        schedulerService.triggerManualCheck(user);
    }

    @PostMapping("/settings/ccl")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveCclSettings(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CclSettingsRequest body) {

        UserAccount channelUser = userResolver.accessibleChannel(username, jwt);
        UserSettings settings = saveSettings(channelUser, s -> {
            s.setCclFeatureEnabled(body.cclEnabled());
            replace(s.getBlockedCcls(), body.blockedCcls());
        });
        publishSettings(channelUser, settings);
    }

    @PostMapping("/settings/tws")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveTwSettings(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody TwSettingsRequest body) {

        UserAccount channelUser = userResolver.accessibleChannel(username, jwt);
        UserSettings settings = saveSettings(channelUser, s -> {
            s.setTwFeatureEnabled(body.enabled());
            replace(s.getBlockedTws(), body.blockedTws());
        });
        publishSettings(channelUser, settings);
    }

    @PostMapping("/settings/no-game")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveNoGameSettings(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody NoGameSettingsRequest body) {

        UserAccount channelUser = userResolver.accessibleChannel(username, jwt);
        UserSettings settings = saveSettings(channelUser, s -> {
            s.setNoGameTwitchGameId(body.twitchGameId());
            s.setNoGameTwitchGameName(body.twitchGameName());
            replace(s.getNoGameCcls(), body.ccls());
            s.setApplyDefaultOnStreamStart(body.applyOnStreamStart());
            s.setApplyDefaultOnNoGame(body.applyOnNoGame());
            s.setApplyDefaultOnStreamEnd(body.applyOnStreamEnd());
        });
        // Nothing detected right now: the new default category applies immediately.
        if (gameStateService.getLastKnownGame(channelUser).isEmpty()) {
            twitchService.resetToDefault(channelUser);
        }
        publishSettings(channelUser, settings);
    }

    @PostMapping("/settings/incomplete-fallback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveIncompleteFallbackSettings(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody IncompleteFallbackRequest body) {

        UserAccount channelUser = userResolver.accessibleChannel(username, jwt);
        UserSettings settings = saveSettings(channelUser, s -> {
            s.setIncompleteFallbackTwitchGameId(body.twitchGameId());
            s.setIncompleteFallbackTwitchGameName(body.twitchGameName());
            replace(s.getIncompleteFallbackCcls(), body.ccls());
        });
        publishSettings(channelUser, settings);
    }

    @PostMapping("/settings/steam-personal-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveSteamPersonalToken(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody SteamTokenRequest body) {

        UserAccount channelUser = userResolver.ownChannel(username, jwt);
        if (body.token() == null || body.token().isBlank()) return;
        String trimmed = body.token().trim();
        channelUser.setSteamPersonalToken(tokenEncryptionService.encrypt(trimmed));
        channelUser.setSteamTokenShared(body.shared());
        userAccountRepository.save(channelUser);
        syncTokenToPool(channelUser, trimmed, body.shared());
        channelEventPublisher.steamProfileChanged(channelUser.getId(), buildSteamProfile(channelUser));
    }

    @PostMapping("/settings/steam-personal-token/sharing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateSteamTokenSharing(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody SteamTokenSharingRequest body) {

        UserAccount channelUser = userResolver.ownChannel(username, jwt);
        if (channelUser.getSteamPersonalToken() == null) return;
        channelUser.setSteamTokenShared(body.shared());
        userAccountRepository.save(channelUser);
        String decryptedToken = tokenEncryptionService.decrypt(channelUser.getSteamPersonalToken());
        syncTokenToPool(channelUser, decryptedToken, body.shared());
        channelEventPublisher.steamProfileChanged(channelUser.getId(), buildSteamProfile(channelUser));
    }

    @Transactional
    @PostMapping("/settings/steam-personal-token/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSteamPersonalToken(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt) {

        UserAccount channelUser = userResolver.ownChannel(username, jwt);
        channelUser.setSteamPersonalToken(null);
        channelUser.setSteamTokenShared(false);
        userAccountRepository.save(channelUser);
        steamApiKeyRepository.deleteByOwner(channelUser);
        if (rotator != null) rotator.refreshKeys();
        channelEventPublisher.steamProfileChanged(channelUser.getId(), buildSteamProfile(channelUser));
    }

    @PostMapping("/settings/delete-account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody DeleteAccountRequest body) {

        UserAccount channelUser = userResolver.ownChannel(username, jwt);
        if (channelUser.getTwitchUsername().equalsIgnoreCase(body.confirmUsername())) {
            accountService.initiateAccountDeletion(channelUser);
        }
    }

    @PostMapping("/settings/cancel-deletion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelDeletion(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt) {

        UserAccount channelUser = userResolver.ownChannel(username, jwt);
        accountService.cancelAccountDeletion(channelUser);
    }

    @PostMapping("/settings/disconnect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnectProvider(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody DisconnectRequest body) {

        UserAccount channelUser = userResolver.ownChannel(username, jwt);
        OAuthToken.Provider provider = OAuthToken.Provider.valueOf(body.provider().toUpperCase());
        accountService.disconnectProvider(channelUser, provider);
        channelEventPublisher.connectionChanged(channelUser.getId(),
                new ProviderConnectionsDto(provider.name(), false, null));
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
        channelEventPublisher.settingsUpdated(channel.getId(), UserSettingsDto.from(settings));
    }

    /** Tells the channel's dashboards about a binding's new state (nothing if it's gone). */
    private void publishBinding(UserAccount channel, UUID bindingId) {
        bindingService.findBinding(channel, bindingId)
                .map(BindingDto::from)
                .ifPresent(dto -> channelEventPublisher.bindingUpserted(channel.getId(), dto));
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
        if (rotator != null) rotator.refreshKeys();
    }

}
