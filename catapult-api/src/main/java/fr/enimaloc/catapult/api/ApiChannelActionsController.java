package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.SteamApiKeyEntry;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.getter.SteamApiClient;
import fr.enimaloc.catapult.getter.SteamApiKeyRotator;
import fr.enimaloc.catapult.service.connections.ProviderConnectionsDto;
import fr.enimaloc.catapult.service.connections.SteamProfileDto;
import fr.enimaloc.catapult.repository.SteamApiKeyRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.AccountService;
import fr.enimaloc.catapult.service.BindingService;
import fr.enimaloc.catapult.service.ChannelAccessService;
import fr.enimaloc.catapult.service.binding.BindingDto;
import fr.enimaloc.catapult.service.settings.UserSettingsDto;
import fr.enimaloc.catapult.service.BotToggleService;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.SchedulerService;
import fr.enimaloc.catapult.service.TwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import java.util.concurrent.TimeUnit;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/channels/{username}")
@RequiredArgsConstructor
public class ApiChannelActionsController {

    private final UserAccountRepository userAccountRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final ChannelAccessService channelAccessService;
    private final BindingService bindingService;
    private final BotToggleService botToggleService;
    private final TwitchService twitchService;
    private final GameStateService gameStateService;
    private final AccountService accountService;
    private final SchedulerService schedulerService;
    private final TokenEncryptionService tokenEncryptionService;
    private final SteamApiKeyRepository steamApiKeyRepository;
    private final fr.enimaloc.catapult.service.notification.ChannelEventPublisher channelEventPublisher;

    @Autowired(required = false)
    private SteamApiKeyRotator rotator;

    @Autowired(required = false)
    private SteamApiClient steamApiClient;

    // ── Binding actions ───────────────────────────────────────────────────────

    @PostMapping("/bindings/{id}/ccl-toggle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleCclEnabled(
            @PathVariable String username,
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CclToggleRequest body) {

        UserAccount viewer = resolveViewer(jwt);
        UserAccount channelUser = resolveChannel(username, viewer);
        bindingService.toggleCclEnabled(channelUser, id, body.enabled());
        bindingService.findBinding(channelUser, id)
                .map(BindingDto::from)
                .ifPresent(dto -> channelEventPublisher.bindingUpserted(channelUser.getId(), dto));
    }

    @PostMapping("/bindings/{id}/ignored-toggle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleIgnored(
            @PathVariable String username,
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody IgnoredToggleRequest body) {

        UserAccount viewer = resolveViewer(jwt);
        UserAccount channelUser = resolveChannel(username, viewer);
        bindingService.toggleIgnored(channelUser, id, body.ignored());
        bindingService.findBinding(channelUser, id)
                .map(BindingDto::from)
                .ifPresent(dto -> channelEventPublisher.bindingUpserted(channelUser.getId(), dto));
    }

    @PostMapping("/bindings/{id}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBinding(
            @PathVariable String username,
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {

        UserAccount viewer = resolveViewer(jwt);
        UserAccount channelUser = resolveChannel(username, viewer);
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

        UserAccount viewer = resolveViewer(jwt);
        UserAccount channelUser = resolveChannel(username, viewer);
        Set<String> ccls = body.ccls() != null ? body.ccls() : Set.of();
        bindingService.updateBinding(channelUser, id, body.twitchGameId(), body.twitchGameName(), ccls, false);
        bindingService.findBinding(channelUser, id)
                .map(BindingDto::from)
                .ifPresent(dto -> channelEventPublisher.bindingUpserted(channelUser.getId(), dto));
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    @PostMapping("/settings/bot")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleBot(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt) {

        UserAccount viewer = resolveViewer(jwt);
        UserAccount user = resolveChannel(username, viewer);
        requireOwner(viewer, user);
        botToggleService.setBotEnabled(user, !user.isBotEnabled());
    }

    // ── Game detection ───────────────────────────────────────────────────────

    @PostMapping("/game/recheck")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recheckGame(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt) {

        UserAccount viewer = resolveViewer(jwt);
        UserAccount user = resolveChannel(username, viewer);
        requireOwner(viewer, user);
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

        UserAccount viewer = resolveViewer(jwt);
        UserAccount channelUser = resolveChannel(username, viewer);
        UserSettings settings = getOrCreateSettings(channelUser);
        settings.setCclFeatureEnabled(body.cclEnabled());
        settings.getBlockedCcls().clear();
        if (body.blockedCcls() != null) settings.getBlockedCcls().addAll(body.blockedCcls());
        userSettingsRepository.save(settings);
        channelEventPublisher.settingsUpdated(channelUser.getId(), UserSettingsDto.from(settings));
    }

    @PostMapping("/settings/tws")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveTwSettings(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody TwSettingsRequest body) {

        UserAccount viewer = resolveViewer(jwt);
        UserAccount channelUser = resolveChannel(username, viewer);
        UserSettings settings = getOrCreateSettings(channelUser);
        settings.setTwFeatureEnabled(body.enabled());
        settings.getBlockedTws().clear();
        if (body.blockedTws() != null) settings.getBlockedTws().addAll(body.blockedTws());
        userSettingsRepository.save(settings);
        channelEventPublisher.settingsUpdated(channelUser.getId(), UserSettingsDto.from(settings));
    }

    @PostMapping("/settings/no-game")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveNoGameSettings(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody NoGameSettingsRequest body) {

        UserAccount viewer = resolveViewer(jwt);
        UserAccount channelUser = resolveChannel(username, viewer);
        UserSettings settings = getOrCreateSettings(channelUser);
        settings.setNoGameTwitchGameId(body.twitchGameId());
        settings.setNoGameTwitchGameName(body.twitchGameName());
        settings.getNoGameCcls().clear();
        if (body.ccls() != null) settings.getNoGameCcls().addAll(body.ccls());
        settings.setApplyDefaultOnStreamStart(body.applyOnStreamStart());
        settings.setApplyDefaultOnNoGame(body.applyOnNoGame());
        settings.setApplyDefaultOnStreamEnd(body.applyOnStreamEnd());
        userSettingsRepository.save(settings);
        if (gameStateService.getLastKnownGame(channelUser).isEmpty()) {
            twitchService.resetToDefault(channelUser);
        }
        channelEventPublisher.settingsUpdated(channelUser.getId(), UserSettingsDto.from(settings));
    }

    @PostMapping("/settings/incomplete-fallback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveIncompleteFallbackSettings(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody IncompleteFallbackRequest body) {

        UserAccount viewer = resolveViewer(jwt);
        UserAccount channelUser = resolveChannel(username, viewer);
        UserSettings settings = getOrCreateSettings(channelUser);
        settings.setIncompleteFallbackTwitchGameId(body.twitchGameId());
        settings.setIncompleteFallbackTwitchGameName(body.twitchGameName());
        settings.getIncompleteFallbackCcls().clear();
        if (body.ccls() != null) settings.getIncompleteFallbackCcls().addAll(body.ccls());
        userSettingsRepository.save(settings);
        channelEventPublisher.settingsUpdated(channelUser.getId(), UserSettingsDto.from(settings));
    }

    @PostMapping("/settings/steam-personal-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveSteamPersonalToken(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody SteamTokenRequest body) {

        UserAccount viewer = resolveViewer(jwt);
        UserAccount channelUser = resolveChannel(username, viewer);
        requireOwner(viewer, channelUser);
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

        UserAccount viewer = resolveViewer(jwt);
        UserAccount channelUser = resolveChannel(username, viewer);
        requireOwner(viewer, channelUser);
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

        UserAccount viewer = resolveViewer(jwt);
        UserAccount channelUser = resolveChannel(username, viewer);
        requireOwner(viewer, channelUser);
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

        UserAccount viewer = resolveViewer(jwt);
        UserAccount channelUser = resolveChannel(username, viewer);
        requireOwner(viewer, channelUser);
        if (channelUser.getTwitchUsername().equalsIgnoreCase(body.confirmUsername())) {
            accountService.initiateAccountDeletion(channelUser);
        }
    }

    @PostMapping("/settings/cancel-deletion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelDeletion(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt) {

        UserAccount viewer = resolveViewer(jwt);
        UserAccount channelUser = resolveChannel(username, viewer);
        requireOwner(viewer, channelUser);
        accountService.cancelAccountDeletion(channelUser);
    }

    @PostMapping("/settings/disconnect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnectProvider(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody DisconnectRequest body) {

        UserAccount viewer = resolveViewer(jwt);
        UserAccount channelUser = resolveChannel(username, viewer);
        requireOwner(viewer, channelUser);
        OAuthToken.Provider provider = OAuthToken.Provider.valueOf(body.provider().toUpperCase());
        accountService.disconnectProvider(channelUser, provider);
        channelEventPublisher.connectionChanged(channelUser.getId(),
                new ProviderConnectionsDto(provider.name(), false, null));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a {@link SteamProfileDto} snapshot from the given channel user.
     * Calls the Steam API to determine profile visibility and offline mode,
     * mirroring the logic in {@code ApiChannelDataController}.
     */
    private SteamProfileDto buildSteamProfile(UserAccount channelUser) {
        boolean hasSteam = steamApiClient != null && channelUser.getSteamId() != null;
        boolean hasPersonalToken = channelUser.getSteamPersonalToken() != null;
        boolean tokenShared = channelUser.isSteamTokenShared();
        long ttlMinutes = steamApiClient != null
                ? steamApiClient.getProfileCacheTtl().toMinutes() : 15L;

        boolean profilePrivate = false;
        boolean offlineMode = false;
        boolean rateLimited = false;

        if (hasSteam && steamApiClient != null) {
            String decryptedToken = hasPersonalToken
                    ? tokenEncryptionService.decrypt(channelUser.getSteamPersonalToken())
                    : null;
            try {
                SteamApiClient.SteamProfileStatus status = steamApiClient
                        .getProfileStatus(channelUser.getSteamId(), decryptedToken)
                        .orTimeout(2, TimeUnit.SECONDS)
                        .exceptionally(e -> new SteamApiClient.SteamProfileStatus(false, false))
                        .join();
                profilePrivate = !status.profilePublic();
                offlineMode = status.offlineMode();
            } catch (Exception ignored) {
            }
            boolean isRateLimited = steamApiClient.isRateLimited()
                    || (rotator != null && rotator.isAllKeysBlocked());
            if (profilePrivate && isRateLimited) {
                rateLimited = true;
                profilePrivate = false;
            }
        }

        return new SteamProfileDto(hasSteam, profilePrivate, offlineMode, rateLimited,
                ttlMinutes, hasPersonalToken, tokenShared);
    }

    private UserAccount resolveViewer(Jwt jwt) {
        UUID viewerId = UUID.fromString(jwt.getSubject());
        return userAccountRepository.findById(viewerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private UserAccount resolveChannel(String username, UserAccount viewer) {
        UserAccount channelUser = userAccountRepository.findByTwitchUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!channelAccessService.canAccess(viewer, channelUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return channelUser;
    }

    private void requireOwner(UserAccount viewer, UserAccount channel) {
        if (!viewer.getId().equals(channel.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private UserSettings getOrCreateSettings(UserAccount user) {
        return userSettingsRepository.findById(user.getId()).orElseGet(() -> {
            UserSettings s = new UserSettings();
            s.setUser(user);
            return s;
        });
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

    // ── Request bodies ────────────────────────────────────────────────────────

    public record CclToggleRequest(boolean enabled) {}
    public record IgnoredToggleRequest(boolean ignored) {}
    public record UpdateBindingRequest(String twitchGameId, String twitchGameName, Set<String> ccls) {}
    public record CclSettingsRequest(boolean cclEnabled, Set<String> blockedCcls) {}
    public record TwSettingsRequest(boolean enabled, Set<String> blockedTws) {}
    public record NoGameSettingsRequest(
            String twitchGameId, String twitchGameName, Set<String> ccls,
            boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd) {}
    public record IncompleteFallbackRequest(String twitchGameId, String twitchGameName, Set<String> ccls) {}
    public record SteamTokenRequest(String token, boolean shared) {}
    public record SteamTokenSharingRequest(boolean shared) {}
    public record DeleteAccountRequest(String confirmUsername) {}
    public record DisconnectRequest(String provider) {}
}
