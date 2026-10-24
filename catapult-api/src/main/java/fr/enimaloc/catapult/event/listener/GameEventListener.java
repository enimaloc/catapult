package fr.enimaloc.catapult.event.listener;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.event.GameDetectedEvent;
import fr.enimaloc.catapult.event.NoGameDetectedEvent;
import fr.enimaloc.catapult.event.StreamOfflineEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.service.binding.BindingService;
import fr.enimaloc.catapult.service.binding.RaidRestoreGameService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameEventListener {

    private final BindingService bindingService;
    private final TwitchService twitchService;
    private final UserSettingsRepository userSettingsRepository;
    private final StreamStateService streamStateService;
    private final ChannelEventPublisher channelEventPublisher;
    private final RaidRestoreGameService raidRestoreGameService;

    @EventListener
    public void onGameDetected(GameDetectedEvent event) {
        UserAccount user = event.getUser();
        log.debug("GameDetectedEvent for user {}: {}", user.getId(), event.getDetectedGame().getSourceName());
        // A new game: a raid's restored game no longer has to be kept
        raidRestoreGameService.releaseHold(user);
        applyDetectedGame(user, event.getDetectedGame());
    }

    /**
     * What a detection of {@code game} does to the channel: its binding (or the ignored / incomplete fallback) is
     * applied when live, stored pending otherwise. Also the path a custom binding's deactivation takes, so the game
     * open at that moment takes over exactly as if it had just been detected.
     */
    public void applyDetectedGame(UserAccount user, DetectedGame game) {
        GameBinding binding = bindingService.resolveOrCreate(user, game);
        // resolveOrCreate may have just inserted a new row (new game ever detected
        // for this user) or refreshed an INCOMPLETE one. Either way the channel
        // page needs to see it — ApiChannelActionsController only publishes for
        // user-initiated mutations, not for scheduler-triggered creations.
        channelEventPublisher.bindingUpserted(user.getId(), binding);
        applyBinding(user, binding);
    }

    /**
     * Puts the binding of the game the user plays on their channel, as a detection does: the no-game fallback when
     * it is ignored, the incomplete fallback when it is INCOMPLETE, else the binding itself, or kept pending until
     * the stream starts.
     */
    public void applyBinding(UserAccount user, GameBinding binding) {
        apply(user, binding, true, updateChannel(user));
    }

    /**
     * {@link #applyBinding} for a live channel only, when an admin game mapping changed its binding: nothing is
     * done, and nothing kept pending, once the stream is offline.
     *
     * @return applied when Twitch took the change, failed when it did not, skipped when there was nothing to send
     *         (stream offline, bot disabled, no fallback configured for an INCOMPLETE binding...).
     */
    public ChannelUpdateOutcome applyBindingIfLive(UserAccount user, GameBinding binding) {
        return apply(user, binding, false, b -> twitchService.updateChannelReporting(user, b));
    }

    /** The detection's own push: {@link TwitchService#updateChannel}, whose outcome nobody reads. */
    private Function<GameBinding, ChannelUpdateOutcome> updateChannel(UserAccount user) {
        return b -> {
            twitchService.updateChannel(user, b);
            return ChannelUpdateOutcome.APPLIED;
        };
    }

    /** {@code push} sends a binding to the user's channel and tells what happened. */
    private ChannelUpdateOutcome apply(UserAccount user, GameBinding binding, boolean pendingWhenOffline,
                                       Function<GameBinding, ChannelUpdateOutcome> push) {
        if (!pendingWhenOffline && !streamStateService.isLive(user)) {
            log.debug("User {} no longer live — binding {} not applied", user.getId(), binding.getId());
            return ChannelUpdateOutcome.SKIPPED;
        }

        if (binding.isIgnored()) {
            log.debug("Binding is ignored — applying no-game fallback for user {}", user.getId());
            return applyNoGameFallback(user, push);
        }

        if (binding.getStatus() == GameBinding.Status.INCOMPLETE) {
            log.debug("Binding is INCOMPLETE — checking fallback for user {}", user.getId());
            return applyIncompleteFallback(user, pendingWhenOffline, push);
        }

        if (streamStateService.isLive(user)) {
            return push.apply(binding);
        }
        if (!pendingWhenOffline) return ChannelUpdateOutcome.SKIPPED;
        streamStateService.storePending(user, binding);
        log.debug("User {} not live — stored pending binding for game {}",
            user.getId(), binding.getSourceName());
        return ChannelUpdateOutcome.SKIPPED;
    }

    @EventListener
    public void onNoGameDetected(NoGameDetectedEvent event) {
        UserAccount user = event.getUser();
        log.debug("NoGameDetectedEvent for user {}", user.getId());
        streamStateService.clearPending(user);
        applyNoGameFallback(user, updateChannel(user));
    }

    @EventListener
    public void onStreamOnline(StreamOnlineEvent event) {
        UserAccount user = event.getUser();
        log.debug("StreamOnlineEvent for user {}", user.getId());
        boolean applyDefault = userSettingsRepository.findById(user.getId())
            .map(UserSettings::isApplyDefaultOnStreamStart)
            .orElse(false);
        if (applyDefault) {
            twitchService.resetToDefault(user);
            streamStateService.clearPending(user);
            return;
        }
        Optional<GameBinding> pending = streamStateService.getPending(user);
        if (pending.isPresent()) {
            twitchService.updateChannel(user, pending.get());
            streamStateService.clearPending(user);
        }
    }

    @EventListener
    public void onStreamOffline(StreamOfflineEvent event) {
        UserAccount user = event.getUser();
        log.debug("StreamOfflineEvent for user {}", user.getId());
        streamStateService.clearPending(user);
        raidRestoreGameService.releaseHold(user);
        userSettingsRepository.findById(user.getId()).ifPresent(settings -> {
            if (settings.isApplyDefaultOnStreamEnd()) {
                twitchService.resetToDefault(user);
            }
        });
    }

    /** The usual "no game" category, applied when live and enabled in the streamer's settings. */
    public void applyNoGameFallback(UserAccount user) {
        applyNoGameFallback(user, updateChannel(user));
    }

    private ChannelUpdateOutcome applyNoGameFallback(UserAccount user,
                                                     Function<GameBinding, ChannelUpdateOutcome> push) {
        if (!streamStateService.isLive(user)) {
            log.debug("User {} not live — skipping no-game fallback", user.getId());
            return ChannelUpdateOutcome.SKIPPED;
        }
        if (raidRestoreGameService.isHoldingNoGameFallback(user)) {
            log.debug("User {} raided out with their last game put back — skipping no-game fallback", user.getId());
            return ChannelUpdateOutcome.SKIPPED;
        }
        Optional<UserSettings> found = userSettingsRepository.findById(user.getId());
        if (found.isEmpty()) return ChannelUpdateOutcome.SKIPPED;
        UserSettings settings = found.get();
        if (!settings.isApplyDefaultOnNoGame()) {
            log.debug("applyDefaultOnNoGame disabled for user {} — skipping", user.getId());
            return ChannelUpdateOutcome.SKIPPED;
        }
        if (settings.getNoGameTwitchGameId() == null || settings.getNoGameTwitchGameId().isBlank()) {
            return ChannelUpdateOutcome.SKIPPED;
        }
        GameBinding fallbackBinding = new GameBinding();
        fallbackBinding.setUser(user);
        fallbackBinding.setSourceType(GameBinding.SourceType.MANUAL);
        fallbackBinding.setSourceName("no-game-fallback");
        fallbackBinding.setTwitchGameId(settings.getNoGameTwitchGameId());
        fallbackBinding.setTwitchGameName(settings.getNoGameTwitchGameName());
        fallbackBinding.setStatus(GameBinding.Status.MANUAL);
        // Checked again right before the PATCH: a raid may have taken the hold since the check above
        if (raidRestoreGameService.isHoldingNoGameFallback(user)) {
            log.debug("User {} raided out meanwhile — skipping no-game fallback", user.getId());
            return ChannelUpdateOutcome.SKIPPED;
        }
        return push.apply(fallbackBinding);
    }

    /** Skipped when no fallback is configured or the stream was offline (the fallback kept pending or not). */
    private ChannelUpdateOutcome applyIncompleteFallback(UserAccount user, boolean pendingWhenOffline,
                                                         Function<GameBinding, ChannelUpdateOutcome> push) {
        Optional<UserSettings> found = userSettingsRepository.findById(user.getId());
        if (found.isEmpty()) return ChannelUpdateOutcome.SKIPPED;
        UserSettings settings = found.get();
        if (settings.getIncompleteFallbackTwitchGameId() == null
                || settings.getIncompleteFallbackTwitchGameId().isBlank()) {
            log.debug("No incomplete fallback configured for user {} — skipping", user.getId());
            return ChannelUpdateOutcome.SKIPPED;
        }
        GameBinding fallback = new GameBinding();
        fallback.setUser(user);
        fallback.setSourceType(GameBinding.SourceType.MANUAL);
        fallback.setSourceName("incomplete-fallback");
        fallback.setTwitchGameId(settings.getIncompleteFallbackTwitchGameId());
        fallback.setTwitchGameName(settings.getIncompleteFallbackTwitchGameName());
        fallback.getCcls().addAll(settings.getIncompleteFallbackCcls());
        fallback.setStatus(GameBinding.Status.MANUAL);

        if (streamStateService.isLive(user)) {
            return push.apply(fallback);
        }
        if (!pendingWhenOffline) return ChannelUpdateOutcome.SKIPPED;
        streamStateService.storePending(user, fallback);
        log.debug("User {} not live — stored incomplete fallback as pending", user.getId());
        return ChannelUpdateOutcome.SKIPPED;
    }
}
