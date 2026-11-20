package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.service.binding.GameStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Forgets the game remembered for a member leaving dry run, on every instance, so the next detection cycle sees a
 * change and applies what the dry run held back.
 */
@Component
@RequiredArgsConstructor
public class DryRunExitListener {

    private final GameStateService gameStateService;

    @EventListener
    public void onDryRunEnded(DryRunEndedEvent event) {
        gameStateService.forgetLastKnownGame(event.userId());
    }
}
