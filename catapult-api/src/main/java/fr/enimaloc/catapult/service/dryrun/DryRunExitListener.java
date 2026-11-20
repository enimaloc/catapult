package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.binding.CustomBindingActivations;
import fr.enimaloc.catapult.service.binding.CustomBindingUserLocks;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.category.CategoryChain;
import fr.enimaloc.catapult.service.category.CategoryOutcome;
import fr.enimaloc.catapult.service.category.CategoryTrigger.CustomBindingTriggered;
import fr.enimaloc.catapult.service.category.CategoryTrigger.GameClosed;
import fr.enimaloc.catapult.service.category.ExtensionLayoutApplier;
import fr.enimaloc.catapult.site.SiteRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Puts on the channel of a member leaving dry run what the dry run held back. On every instance, the extension layout
 * taken as in place (the dry run never sent it) is forgotten, and so is the game remembered, so that the next
 * detection cycle sees a change and applies it, extensions included.
 *
 * <p>The cycle only acts on a change, so what it would never see again is re-applied once, at once, on the active
 * site (the one that writes): an active custom binding's category and layout (the game open meanwhile is then kept,
 * as a detection of it would interrupt the binding), or, with no game open, what closing the game does (the "no game"
 * category and layout), only when that is the last decision the dry run held back ({@link DryRunHeldDecisions}): a
 * category the streamer set by hand meanwhile, or with no game closed, stays. The reset is not done either when
 * nothing has been observed since this instance started: no game known then does not mean no game open, and the
 * first cycle decides.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DryRunExitListener {

    private final GameStateService gameStateService;
    private final ExtensionLayoutApplier extensionLayouts;
    private final UserAccountRepository userAccountRepository;
    private final CustomBindingActivations customBindingActivations;
    private final CategoryChain categoryChain;
    private final SiteRole siteRole;
    private final DryRunHeldDecisions heldDecisions;
    private final CustomBindingUserLocks customBindingLocks;

    @EventListener
    public void onDryRunEnded(DryRunEndedEvent event) {
        UUID userId = event.userId();
        extensionLayouts.forget(userId);
        Optional<DryRunHeldDecisions.Held> held = heldDecisions.take(userId);
        // what decides the custom binding (a manual choice, the scheduler) waits: the binding read stays the one
        // in force until re-applied
        customBindingLocks.run(userId, () -> leave(userId, held));
    }

    private void leave(UUID userId, Optional<DryRunHeldDecisions.Held> held) {
        Optional<UserAccount> user = Optional.empty();
        Optional<CustomBindingActivation> active = Optional.empty();
        try {
            user = userAccountRepository.findById(userId);
            active = user.flatMap(customBindingActivations::active);
        } catch (RuntimeException e) {
            log.warn("Could not read the state of {} leaving dry run: {}", userId, e.toString());
        }
        boolean gameKnown = user.flatMap(gameStateService::getLastKnownGame).isPresent();
        if (active.isEmpty()) {
            gameStateService.forgetLastKnownGame(userId);
        }
        if (user.isEmpty() || !siteRole.isActive()) {
            return;
        }
        try {
            reapply(user.get(), active, gameKnown, held);
        } catch (RuntimeException e) {
            log.warn("Could not re-apply the channel of {} leaving dry run: {}", userId, e.toString());
        }
    }

    private void reapply(UserAccount user, Optional<CustomBindingActivation> active, boolean gameKnown,
                         Optional<DryRunHeldDecisions.Held> held) {
        CategoryOutcome outcome;
        if (active.isPresent()) {
            outcome = categoryChain.apply(user, new CustomBindingTriggered(active.get().getCustomBinding()));
        } else if (!gameKnown && gameStateService.isObserved(user)
                && held.map(DryRunHeldDecisions.Held::noGame).orElse(false)) {
            outcome = categoryChain.apply(user, new GameClosed());
        } else {
            // a game open, the state unknown since the restart (the next detection cycle applies it), or no "no game"
            // reset held: the category is the one the streamer set, or already the one Catapult would set
            return;
        }
        log.info("Channel of {} re-applied after its dry run: {}", user.getId(), outcome);
    }
}
