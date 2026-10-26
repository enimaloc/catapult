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
import fr.enimaloc.catapult.service.binding.CustomBindingActivations;
import fr.enimaloc.catapult.service.binding.RaidRestoreHold;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.category.CategoryChain;
import fr.enimaloc.catapult.service.category.CategoryOutcome;
import fr.enimaloc.catapult.service.category.CategoryRule;
import fr.enimaloc.catapult.service.category.CategoryTrigger.GameClosed;
import fr.enimaloc.catapult.service.category.CategoryTrigger.GameDetected;
import fr.enimaloc.catapult.service.category.CategoryTrigger.NoGameAfterCustomBinding;
import fr.enimaloc.catapult.service.category.CategoryTrigger.StreamEnded;
import fr.enimaloc.catapult.service.category.CategoryTrigger.StreamStarted;
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
    private final RaidRestoreHold raidRestoreHold;
    private final CustomBindingActivations customBindingActivations;
    private final CategoryChain categoryChain;

    @EventListener
    public void onGameDetected(GameDetectedEvent event) {
        UserAccount user = event.getUser();
        log.debug("GameDetectedEvent for user {}: {}", user.getId(), event.getDetectedGame().getSourceName());
        // A new game: a raid's restored game no longer has to be kept
        raidRestoreHold.release(user);
        switch (categoryChain.detectionGuard(user, event.isFirstSinceStart())) {
            case KEEPS_CUSTOM_BINDING -> {
                // Ignored: the game's binding is created or refreshed for the channel page, the channel left alone
                GameBinding binding = bindingService.resolveOrCreate(user, event.getDetectedGame());
                channelEventPublisher.bindingUpserted(user.getId(), binding);
                log.debug("Custom binding active for user {} ({}) — detection not applied", user.getId(),
                    event.isFirstSinceStart() ? "first detection since start" : "uninterruptible");
                return;
            }
            case INTERRUPTS_CUSTOM_BINDING -> {
                log.debug("Detection interrupts the custom binding of user {}", user.getId());
                customBindingActivations.clear(user);
            }
            case NO_CUSTOM_BINDING -> { }
        }
        applyDetectedGame(user, event.getDetectedGame()).orThrow();
    }

    /**
     * What a detection of {@code game} does to the channel: its binding (or the ignored / incomplete fallback) is
     * applied when live, stored pending otherwise. Also the path a custom binding's deactivation takes, so the game
     * open at that moment takes over exactly as if it had just been detected.
     */
    public CategoryOutcome applyDetectedGame(UserAccount user, DetectedGame game) {
        GameBinding binding = bindingService.resolveOrCreate(user, game);
        // resolveOrCreate may have just inserted a new row (new game ever detected
        // for this user) or refreshed an INCOMPLETE one. Either way the channel
        // page needs to see it — ApiChannelActionsController only publishes for
        // user-initiated mutations, not for scheduler-triggered creations.
        channelEventPublisher.bindingUpserted(user.getId(), binding);
        return categoryChain.apply(user, new GameDetected(binding));
    }

    /**
     * A detection's binding rules for a live channel only, when an admin game mapping changed its binding: nothing is
     * done, and nothing kept pending, once the stream is offline.
     *
     * @return applied when Twitch took the change, failed when it did not, skipped when there was nothing to send
     *         (stream offline, bot disabled, no fallback configured for an INCOMPLETE binding...).
     */
    public ChannelUpdateOutcome applyBindingIfLive(UserAccount user, GameBinding binding) {
        if (customBindingActivations.active(user).isPresent()) {
            // Like an edit of the binding: only saved, it applies once the custom binding is deactivated
            log.debug("Custom binding active for user {} — binding {} not applied", user.getId(), binding.getId());
            return ChannelUpdateOutcome.SKIPPED;
        }
        return apply(user, binding, false, b -> twitchService.updateChannelReporting(user, b));
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
        categoryChain.apply(user, new GameClosed()).orThrow();
    }

    @EventListener
    public void onStreamOnline(StreamOnlineEvent event) {
        UserAccount user = event.getUser();
        log.debug("StreamOnlineEvent for user {}", user.getId());
        CategoryOutcome outcome = categoryChain.apply(user, new StreamStarted()).orThrow();
        // A custom binding keeps the pending one stored for when it is deactivated
        if (outcome.rule() != CategoryRule.CUSTOM_BINDING) {
            streamStateService.clearPending(user);
        }
    }

    @EventListener
    public void onStreamOffline(StreamOfflineEvent event) {
        UserAccount user = event.getUser();
        log.debug("StreamOfflineEvent for user {}", user.getId());
        // A custom binding, interruptible or not, ends with the stream; then the usual end of stream
        customBindingActivations.clear(user);
        streamStateService.clearPending(user);
        raidRestoreHold.release(user);
        categoryChain.apply(user, new StreamEnded()).orThrow();
    }

    /** The usual "no game" category after a custom binding ended, applied when live and enabled in the settings. */
    public CategoryOutcome applyNoGameFallback(UserAccount user) {
        return categoryChain.apply(user, new NoGameAfterCustomBinding());
    }

    private ChannelUpdateOutcome applyNoGameFallback(UserAccount user,
                                                     Function<GameBinding, ChannelUpdateOutcome> push) {
        if (!streamStateService.isLive(user)) {
            log.debug("User {} not live — skipping no-game fallback", user.getId());
            return ChannelUpdateOutcome.SKIPPED;
        }
        if (raidRestoreHold.isHolding(user)) {
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
        if (raidRestoreHold.isHolding(user)) {
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
