package fr.enimaloc.catapult.service.category;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.service.binding.CustomBindingActivations;
import fr.enimaloc.catapult.service.binding.RaidRestoreHold;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.category.CategoryOutcome.Reason;
import fr.enimaloc.catapult.service.category.CategoryTrigger.GameClosed;
import fr.enimaloc.catapult.service.category.CategoryTrigger.GameDetected;
import fr.enimaloc.catapult.service.category.CategoryTrigger.NoGameAfterCustomBinding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The one place that decides a channel's Twitch category. Each {@link CategoryTrigger} has its own rule list, read
 * in today's order and once: the state (live, custom binding, settings, raid hold) is read while deciding, and the
 * decision applied right after, never cached.
 *
 * <p>Never runs as anyone ({@code DebugContext}): the trigger's own attribution stays.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CategoryChain {

    /** {@link GameBinding#getSourceName()} of the transient binding the "no game" category is sent through. */
    public static final String NO_GAME_FALLBACK_SOURCE = "no-game-fallback";
    /** {@link GameBinding#getSourceName()} of the transient binding the incomplete fallback is sent through. */
    public static final String INCOMPLETE_FALLBACK_SOURCE = "incomplete-fallback";

    /** What an active custom binding makes of a detection. */
    public enum DetectionGuard {
        /** No custom binding active: the detection applies. */
        NO_CUSTOM_BINDING,
        /** The custom binding stays (uninterruptible, or the first detection since start): the channel left alone. */
        KEEPS_CUSTOM_BINDING,
        /** The detection ends the custom binding, then applies. */
        INTERRUPTS_CUSTOM_BINDING
    }

    private final CustomBindingActivations customBindingActivations;
    private final StreamStateService streamStateService;
    private final UserSettingsRepository userSettingsRepository;
    private final RaidRestoreHold raidRestoreHold;
    private final CategoryApplier applier;

    /** {@link #decide}, then applied right away. */
    public CategoryOutcome apply(UserAccount user, CategoryTrigger trigger) {
        return applier.apply(user, decide(user, trigger));
    }

    public CategoryDecision decide(UserAccount user, CategoryTrigger trigger) {
        return switch (trigger) {
            case GameDetected detected -> gameBinding(user, detected.binding(), true);
            case GameClosed closed -> gameClosed(user);
            case NoGameAfterCustomBinding after -> noGameFallback(user);
        };
    }

    /**
     * Whether the active custom binding, if any, survives a detection. The first detection since catapult-api
     * started follows an unknown state (the game state is kept in memory), not a game launch: the game may well have
     * been open since before the activation. Any later detection interrupts an interruptible binding, the same game
     * relaunched too.
     */
    public DetectionGuard detectionGuard(UserAccount user, boolean firstSinceStart) {
        Optional<CustomBindingActivation> active = customBindingActivations.active(user);
        if (active.isEmpty()) return DetectionGuard.NO_CUSTOM_BINDING;
        if (active.get().getCustomBinding().isUninterruptible() || firstSinceStart) {
            return DetectionGuard.KEEPS_CUSTOM_BINDING;
        }
        return DetectionGuard.INTERRUPTS_CUSTOM_BINDING;
    }

    private CategoryDecision gameClosed(UserAccount user) {
        if (customBindingActivations.active(user).isPresent()) {
            // Closing the game never interrupts a custom binding, and the "no game" category would overwrite it
            log.debug("Custom binding active for user {} — skipping no-game fallback", user.getId());
            return CategoryDecision.none(CategoryRule.CUSTOM_BINDING, Reason.CUSTOM_BINDING_ACTIVE);
        }
        return noGameFallback(user);
    }

    /**
     * The game's binding: the no-game fallback when it is ignored, the incomplete fallback when it is INCOMPLETE,
     * else the binding itself when live, or kept pending until the stream starts ({@code pendingWhenOffline}).
     */
    private CategoryDecision gameBinding(UserAccount user, GameBinding binding, boolean pendingWhenOffline) {
        if (!pendingWhenOffline && !streamStateService.isLive(user)) {
            log.debug("User {} no longer live — binding {} not applied", user.getId(), binding.getId());
            return CategoryDecision.none(CategoryRule.GAME_BINDING, Reason.OFFLINE);
        }

        if (binding.isIgnored()) {
            log.debug("Binding is ignored — applying no-game fallback for user {}", user.getId());
            return noGameFallback(user);
        }

        if (binding.getStatus() == GameBinding.Status.INCOMPLETE) {
            log.debug("Binding is INCOMPLETE — checking fallback for user {}", user.getId());
            return incompleteFallback(user, pendingWhenOffline);
        }

        if (streamStateService.isLive(user)) {
            return CategoryDecision.update(CategoryRule.GAME_BINDING, binding);
        }
        if (!pendingWhenOffline) return CategoryDecision.none(CategoryRule.GAME_BINDING, Reason.OFFLINE);
        log.debug("User {} not live — storing pending binding for game {}", user.getId(), binding.getSourceName());
        return CategoryDecision.storePending(CategoryRule.GAME_BINDING, binding);
    }

    /** The usual "no game" category, applied when live and enabled in the streamer's settings. */
    private CategoryDecision noGameFallback(UserAccount user) {
        if (!streamStateService.isLive(user)) {
            log.debug("User {} not live — skipping no-game fallback", user.getId());
            return CategoryDecision.none(CategoryRule.NO_GAME_FALLBACK, Reason.OFFLINE);
        }
        if (raidRestoreHold.isHolding(user)) {
            log.debug("User {} raided out with their last game put back — skipping no-game fallback", user.getId());
            return CategoryDecision.none(CategoryRule.NO_GAME_FALLBACK, Reason.RAID_HOLD);
        }
        Optional<UserSettings> found = userSettingsRepository.findById(user.getId());
        if (found.isEmpty()) return CategoryDecision.none(CategoryRule.NO_GAME_FALLBACK, Reason.NO_SETTINGS);
        UserSettings settings = found.get();
        if (!settings.isApplyDefaultOnNoGame()) {
            log.debug("applyDefaultOnNoGame disabled for user {} — skipping", user.getId());
            return CategoryDecision.none(CategoryRule.NO_GAME_FALLBACK, Reason.NO_GAME_FALLBACK_OFF);
        }
        if (settings.getNoGameTwitchGameId() == null || settings.getNoGameTwitchGameId().isBlank()) {
            return CategoryDecision.none(CategoryRule.NO_GAME_FALLBACK, Reason.NOTHING_CONFIGURED);
        }
        GameBinding fallbackBinding = new GameBinding();
        fallbackBinding.setUser(user);
        fallbackBinding.setSourceType(GameBinding.SourceType.MANUAL);
        fallbackBinding.setSourceName(NO_GAME_FALLBACK_SOURCE);
        fallbackBinding.setTwitchGameId(settings.getNoGameTwitchGameId());
        fallbackBinding.setTwitchGameName(settings.getNoGameTwitchGameName());
        fallbackBinding.setStatus(GameBinding.Status.MANUAL);
        // Checked again right before the PATCH: a raid may have taken the hold since the check above
        if (raidRestoreHold.isHolding(user)) {
            log.debug("User {} raided out meanwhile — skipping no-game fallback", user.getId());
            return CategoryDecision.none(CategoryRule.NO_GAME_FALLBACK, Reason.RAID_HOLD);
        }
        return CategoryDecision.update(CategoryRule.NO_GAME_FALLBACK, fallbackBinding);
    }

    /** Nothing when no fallback is configured, or the stream is offline and nothing is kept pending. */
    private CategoryDecision incompleteFallback(UserAccount user, boolean pendingWhenOffline) {
        Optional<UserSettings> found = userSettingsRepository.findById(user.getId());
        if (found.isEmpty()) return CategoryDecision.none(CategoryRule.INCOMPLETE_FALLBACK, Reason.NO_SETTINGS);
        UserSettings settings = found.get();
        if (settings.getIncompleteFallbackTwitchGameId() == null
                || settings.getIncompleteFallbackTwitchGameId().isBlank()) {
            log.debug("No incomplete fallback configured for user {} — skipping", user.getId());
            return CategoryDecision.none(CategoryRule.INCOMPLETE_FALLBACK, Reason.NOTHING_CONFIGURED);
        }
        GameBinding fallback = new GameBinding();
        fallback.setUser(user);
        fallback.setSourceType(GameBinding.SourceType.MANUAL);
        fallback.setSourceName(INCOMPLETE_FALLBACK_SOURCE);
        fallback.setTwitchGameId(settings.getIncompleteFallbackTwitchGameId());
        fallback.setTwitchGameName(settings.getIncompleteFallbackTwitchGameName());
        fallback.getCcls().addAll(settings.getIncompleteFallbackCcls());
        fallback.setStatus(GameBinding.Status.MANUAL);

        if (streamStateService.isLive(user)) {
            return CategoryDecision.update(CategoryRule.INCOMPLETE_FALLBACK, fallback);
        }
        if (!pendingWhenOffline) return CategoryDecision.none(CategoryRule.INCOMPLETE_FALLBACK, Reason.OFFLINE);
        log.debug("User {} not live — storing incomplete fallback as pending", user.getId());
        return CategoryDecision.storePending(CategoryRule.INCOMPLETE_FALLBACK, fallback);
    }
}
