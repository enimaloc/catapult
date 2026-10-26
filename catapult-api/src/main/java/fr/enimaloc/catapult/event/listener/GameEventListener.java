package fr.enimaloc.catapult.event.listener;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.event.GameDetectedEvent;
import fr.enimaloc.catapult.event.NoGameDetectedEvent;
import fr.enimaloc.catapult.event.StreamOfflineEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.binding.BindingService;
import fr.enimaloc.catapult.service.binding.CustomBindingActivations;
import fr.enimaloc.catapult.service.binding.RaidRestoreHold;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.category.CategoryChain;
import fr.enimaloc.catapult.service.category.CategoryOutcome;
import fr.enimaloc.catapult.service.category.CategoryRule;
import fr.enimaloc.catapult.service.category.CategoryTrigger.GameClosed;
import fr.enimaloc.catapult.service.category.CategoryTrigger.GameDetected;
import fr.enimaloc.catapult.service.category.CategoryTrigger.StreamEnded;
import fr.enimaloc.catapult.service.category.CategoryTrigger.StreamStarted;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameEventListener {

    private final BindingService bindingService;
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
}
