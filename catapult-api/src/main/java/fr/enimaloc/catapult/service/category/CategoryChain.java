package fr.enimaloc.catapult.service.category;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.service.binding.CustomBindingActivations;
import fr.enimaloc.catapult.service.binding.GameBindingLookup;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.binding.RaidRestoreGameService;
import fr.enimaloc.catapult.service.binding.RaidRestoreGateService;
import fr.enimaloc.catapult.service.binding.RaidRestoreHold;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.category.CategoryOutcome.Reason;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import fr.enimaloc.catapult.service.category.CategoryTrigger.BindingEdited;
import fr.enimaloc.catapult.service.category.CategoryTrigger.CustomBindingTriggered;
import fr.enimaloc.catapult.service.category.CategoryTrigger.GameClosed;
import fr.enimaloc.catapult.service.category.CategoryTrigger.GameDetected;
import fr.enimaloc.catapult.service.category.CategoryTrigger.ManualCategory;
import fr.enimaloc.catapult.service.category.CategoryTrigger.MappingChanged;
import fr.enimaloc.catapult.service.category.CategoryTrigger.NoGameSettingsSaved;
import fr.enimaloc.catapult.service.category.CategoryTrigger.NoGameAfterCustomBinding;
import fr.enimaloc.catapult.service.category.CategoryTrigger.RaidedOut;
import fr.enimaloc.catapult.service.category.CategoryTrigger.RevertCategory;
import fr.enimaloc.catapult.service.category.CategoryTrigger.StreamEnded;
import fr.enimaloc.catapult.service.category.CategoryTrigger.StreamStarted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * The one place that decides a channel's Twitch category. Each {@link CategoryTrigger} has its own rule list, read
 * in today's order and once: the state (live, custom binding, settings, raid hold) is read while deciding, and the
 * decision applied right after, never cached.
 *
 * <p>Never runs as anyone ({@code DebugContext}): the trigger attributes the change, to the channel's owner when
 * someone else acts for the channel (a moderator's or admin's edit, an admin mapping, a Twitchat action).</p>
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
    private final GameStateService gameStateService;
    private final UserAccountRepository userAccountRepository;
    private final CategoryApplier applier;
    private final RaidRestoreGateService raidRestoreGate;
    private final GameBindingLookup gameBindingLookup;
    private final ExtensionLayoutApplier extensionLayouts;

    /**
     * {@link #decide}, then applied right away. When the category is applied, the channel's Twitch extensions follow
     * the configuration it comes from; the extensions never change the returned outcome.
     */
    public CategoryOutcome apply(UserAccount user, CategoryTrigger trigger) {
        CategoryDecision decision = decide(user, trigger);
        CategoryOutcome outcome = applier.apply(user, decision);
        switchExtensions(user, trigger, decision, outcome);
        return outcome;
    }

    /**
     * The configuration in force changes only on the triggers that switch the category: an applied category puts its
     * extension layout. Leaving the game puts back the extensions the game's configuration replaced, then puts the
     * "no game" layout, whether its category is applied or not; a custom binding or a raid that keeps the game keeps
     * them. The end of the stream puts every replaced extension back. Edits, manual changes and raids leave the
     * extensions alone.
     */
    private void switchExtensions(UserAccount user, CategoryTrigger trigger, CategoryDecision decision,
                                  CategoryOutcome outcome) {
        if (trigger instanceof StreamEnded) {
            extensionLayouts.restore(user);
            return;
        }
        boolean leavesGame = trigger instanceof GameClosed || trigger instanceof NoGameAfterCustomBinding;
        if (leavesGame) {
            if (keepsTheGame(decision.reason()) || keepsTheGame(outcome.reason())) return;
            userSettingsRepository.findById(user.getId()).ifPresentOrElse(
                    settings -> extensionLayouts.applyOnSwitch(user, ExtensionLayoutApplier.NO_GAME_KEY,
                            settings.getNoGameExtensionSlots()),
                    () -> extensionLayouts.restore(user));
            return;
        }
        boolean switches = trigger instanceof GameDetected || trigger instanceof StreamStarted
                || trigger instanceof CustomBindingTriggered || trigger instanceof NoGameSettingsSaved;
        if (!switches || outcome.status() != ChannelUpdateOutcome.APPLIED) return;
        switch (decision.action()) {
            case UPDATE_CHANNEL -> extensionLayouts.applyOnSwitch(user,
                    ExtensionLayoutApplier.keyOf(decision.binding()), decision.binding().getExtensionSlots());
            case RESET_TO_DEFAULT -> userSettingsRepository.findById(user.getId()).ifPresent(settings ->
                    extensionLayouts.applyOnSwitch(user, ExtensionLayoutApplier.NO_GAME_KEY,
                            settings.getNoGameExtensionSlots()));
            default -> { }
        }
    }

    private static boolean keepsTheGame(Reason reason) {
        return reason == Reason.CUSTOM_BINDING_ACTIVE || reason == Reason.RAID_HOLD;
    }

    public CategoryDecision decide(UserAccount user, CategoryTrigger trigger) {
        return switch (trigger) {
            case GameDetected detected -> gameBinding(user, detected.binding(), true);
            case GameClosed closed -> gameClosed(user);
            case NoGameAfterCustomBinding after -> noGameFallback(user);
            case StreamStarted started -> streamStarted(user);
            case StreamEnded ended -> streamEnded(user);
            case BindingEdited edited -> bindingEdited(user, edited.binding());
            case MappingChanged changed -> mappingChanged(user, changed.binding());
            case CustomBindingTriggered triggered -> customBindingTriggered(user, triggered.binding());
            case ManualCategory manual -> CategoryDecision.update(CategoryRule.MANUAL_CHOICE, manual.binding());
            case RevertCategory revert -> CategoryDecision.setCategory(CategoryRule.MANUAL_CHOICE,
                    revert.twitchGameId(), revert.twitchGameName());
            case NoGameSettingsSaved saved -> noGameSettingsSaved(user, saved.applyDefaultOnNoGame());
            case RaidedOut raided -> raidedOut(user, raided.currentCategoryId());
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

    /**
     * The edited binding, when it is the game played now and no custom binding is active (then it only applies once
     * the custom binding is deactivated), by the detection's rules: the binding when live, kept pending offline, the
     * no-game fallback when ignored, the incomplete fallback when INCOMPLETE. The custom binding is read only for the
     * game played now.
     */
    private CategoryDecision bindingEdited(UserAccount user, GameBinding binding) {
        if (!isActiveBinding(user, binding)) {
            return CategoryDecision.none(CategoryRule.GAME_BINDING, Reason.NOT_PLAYED_NOW);
        }
        if (customBindingActivations.active(user).isPresent()) {
            return CategoryDecision.none(CategoryRule.CUSTOM_BINDING, Reason.CUSTOM_BINDING_ACTIVE);
        }
        return gameBinding(user, binding, true);
    }

    private boolean isActiveBinding(UserAccount user, GameBinding binding) {
        return gameStateService.getLastKnownGame(user)
                .map(active -> active.getSourceType() == binding.getSourceType()
                        && active.getSourceId().equals(binding.getSourceId()))
                .orElse(false);
    }

    private CategoryDecision mappingChanged(UserAccount user, GameBinding binding) {
        if (customBindingActivations.active(user).isPresent()) {
            // Like an edit of the binding: only saved, it applies once the custom binding is deactivated
            log.debug("Custom binding active for user {} — binding {} not applied", user.getId(), binding.getId());
            return CategoryDecision.none(CategoryRule.CUSTOM_BINDING, Reason.CUSTOM_BINDING_ACTIVE);
        }
        return gameBinding(user, binding, false);
    }

    /** The custom binding's category, the bot's state checked first so a paused bot is not taken for a Twitch problem. */
    private CategoryDecision customBindingTriggered(UserAccount user, CustomBinding binding) {
        boolean botActive = userAccountRepository.findById(user.getId())
                .map(account -> account.isBotEnabled() && account.getStatus() == UserAccount.Status.ACTIVE)
                .orElse(false);
        if (!botActive) return CategoryDecision.none(CategoryRule.CUSTOM_BINDING, Reason.BOT_INACTIVE);
        return CategoryDecision.update(CategoryRule.CUSTOM_BINDING, binding.toGameBinding(user));
    }

    /**
     * The default category at once, only when the "no game" fallback would apply now: nothing detected, then no
     * custom binding holding the channel, live, no raid hold, and {@code applyDefaultOnNoGame} on (the value just
     * saved). Otherwise the settings are only saved.
     */
    private CategoryDecision noGameSettingsSaved(UserAccount user, boolean applyDefaultOnNoGame) {
        if (gameStateService.getLastKnownGame(user).isPresent()) {
            return CategoryDecision.none(CategoryRule.DEFAULT_CATEGORY, Reason.GAME_DETECTED);
        }
        if (customBindingActivations.active(user).isPresent()) {
            return CategoryDecision.none(CategoryRule.CUSTOM_BINDING, Reason.CUSTOM_BINDING_ACTIVE);
        }
        if (!streamStateService.isLive(user)) {
            return CategoryDecision.none(CategoryRule.DEFAULT_CATEGORY, Reason.OFFLINE);
        }
        if (raidRestoreHold.isHolding(user)) {
            return CategoryDecision.none(CategoryRule.DEFAULT_CATEGORY, Reason.RAID_HOLD);
        }
        if (!applyDefaultOnNoGame) {
            return CategoryDecision.none(CategoryRule.DEFAULT_CATEGORY, Reason.NO_GAME_FALLBACK_OFF);
        }
        return CategoryDecision.reset(CategoryRule.DEFAULT_CATEGORY);
    }

    /**
     * The game detected within the streamer's delay, put back. Takes no hold (the caller does, for an update and for
     * an "already on it"); no live check. The binding is looked up read-only.
     */
    private CategoryDecision raidedOut(UserAccount user, String currentCategoryId) {
        Integer minutes = userSettingsRepository.findById(user.getId())
                .map(UserSettings::getRaidRestoreGameMinutes)
                .filter(m -> m >= RaidRestoreGameService.MIN_MINUTES)
                .orElse(null);
        if (minutes == null || !raidRestoreGate.isAvailableReadOnly(user)) {
            return CategoryDecision.none(CategoryRule.RAID_RESTORE, Reason.RAID_RESTORE_OFF);
        }
        if (customBindingActivations.active(user).isPresent()) {
            // The channel is on the streamer's custom binding, not on the "no game" category: nothing to put back
            log.debug("Raid by user {}: custom binding active, game not put back", user.getId());
            return CategoryDecision.none(CategoryRule.CUSTOM_BINDING, Reason.CUSTOM_BINDING_ACTIVE);
        }

        Optional<DetectedGame> recent = gameStateService.recentlyDetectedGame(user, Duration.ofMinutes(minutes));
        Optional<GameBinding> binding = recent
                .flatMap(game -> gameBindingLookup.findByDetectedGame(user, game))
                .filter(b -> !b.isIgnored() && b.getStatus() != GameBinding.Status.INCOMPLETE)
                .filter(b -> b.getTwitchGameId() != null && !b.getTwitchGameId().isBlank());
        if (binding.isEmpty()) {
            log.debug("Raid by user {}: no game detected within {} min to put back", user.getId(), minutes);
            return CategoryDecision.none(CategoryRule.RAID_RESTORE, Reason.NOTHING_TO_RESTORE);
        }
        if (binding.get().getTwitchGameId().equals(currentCategoryId)) {
            log.debug("Raid by user {}: already on {}", user.getId(), binding.get().getTwitchGameName());
            return CategoryDecision.none(CategoryRule.RAID_RESTORE, Reason.ALREADY_ON_CATEGORY);
        }
        return CategoryDecision.update(CategoryRule.RAID_RESTORE, binding.get());
    }

    private CategoryDecision streamStarted(UserAccount user) {
        Optional<CustomBindingActivation> active = customBindingActivations.active(user);
        if (active.isPresent()) {
            // The prepared category wins over the default and the pending one; the pending one stays stored for
            // when the binding is deactivated
            log.debug("Custom binding {} active for user {} — re-applied at stream start",
                    active.get().getCustomBinding().getId(), user.getId());
            return CategoryDecision.update(CategoryRule.CUSTOM_BINDING,
                    active.get().getCustomBinding().toGameBinding(user));
        }
        boolean applyDefault = userSettingsRepository.findById(user.getId())
                .map(UserSettings::isApplyDefaultOnStreamStart)
                .orElse(false);
        if (applyDefault) {
            return CategoryDecision.reset(CategoryRule.DEFAULT_CATEGORY);
        }
        return streamStateService.getPending(user)
                .map(pending -> CategoryDecision.update(CategoryRule.PENDING, pending))
                .orElseGet(() -> CategoryDecision.none(CategoryRule.PENDING, Reason.NOTHING_PENDING));
    }

    private CategoryDecision streamEnded(UserAccount user) {
        boolean applyDefault = userSettingsRepository.findById(user.getId())
                .map(UserSettings::isApplyDefaultOnStreamEnd)
                .orElse(false);
        return applyDefault
                ? CategoryDecision.reset(CategoryRule.DEFAULT_CATEGORY)
                : CategoryDecision.none(CategoryRule.DEFAULT_CATEGORY, Reason.DEFAULT_OFF);
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
        // The configured "no game" CCLs, sent like the reset sends them: only when there are some (the channel's
        // CCLs are otherwise left as they are), under the same CCL feature switch (applied by the Twitch layer), and
        // without the blocklist (a transient binding)
        fallbackBinding.getCcls().addAll(settings.getNoGameCcls());
        fallbackBinding.setCclEnabled(!settings.getNoGameCcls().isEmpty());
        fallbackBinding.getExtensionSlots().addAll(settings.getNoGameExtensionSlots());
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
        // Like the no-game fallback and the reset: CCLs are sent only when some are configured, otherwise the
        // channel's CCLs are left as they are
        fallback.getCcls().addAll(settings.getIncompleteFallbackCcls());
        fallback.setCclEnabled(!settings.getIncompleteFallbackCcls().isEmpty());
        fallback.getExtensionSlots().addAll(settings.getIncompleteFallbackExtensionSlots());
        fallback.setStatus(GameBinding.Status.MANUAL);

        if (streamStateService.isLive(user)) {
            return CategoryDecision.update(CategoryRule.INCOMPLETE_FALLBACK, fallback);
        }
        if (!pendingWhenOffline) return CategoryDecision.none(CategoryRule.INCOMPLETE_FALLBACK, Reason.OFFLINE);
        log.debug("User {} not live — storing incomplete fallback as pending", user.getId());
        return CategoryDecision.storePending(CategoryRule.INCOMPLETE_FALLBACK, fallback);
    }
}
