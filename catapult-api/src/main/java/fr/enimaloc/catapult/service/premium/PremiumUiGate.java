package fr.enimaloc.catapult.service.premium;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Whether a member sees premium in the UI (its page, the menu entry, the custom bindings' counter):
 * experiment {@value #EXPERIMENT_KEY}. Only the display is gated; the tiers apply to everyone.
 */
@Service
@RequiredArgsConstructor
public class PremiumUiGate {

    public static final String EXPERIMENT_KEY = "premium.ui";

    private final ExperimentService experimentService;

    /** Created DRAFT, so closed to everyone until the admin opens it. */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureExperiment() {
        experimentService.ensureExists(EXPERIMENT_KEY, "enabled");
    }

    /** User-facing paths only: may assign the member to the experiment. */
    public boolean isOpenFor(UserAccount user) {
        return experimentService.evaluateGate(user, EXPERIMENT_KEY);
    }
}
