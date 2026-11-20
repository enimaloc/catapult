package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.category.ExtensionLayoutApplier;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Forgets, for a member leaving dry run and on every instance, the game remembered and the extension layout taken
 * as in place (the dry run never sent it), so the next detection cycle sees a change and applies what the dry run
 * held back, extensions included.
 */
@Component
@RequiredArgsConstructor
public class DryRunExitListener {

    private final GameStateService gameStateService;
    private final ExtensionLayoutApplier extensionLayouts;

    @EventListener
    public void onDryRunEnded(DryRunEndedEvent event) {
        gameStateService.forgetLastKnownGame(event.userId());
        extensionLayouts.forget(event.userId());
    }
}
