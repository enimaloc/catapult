package fr.enimaloc.catapult.service.drops;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Whether a channel gets the Twitch drops (chip, filter, public route, title): experiment {@value #EXPERIMENT_KEY}. */
@Service
@RequiredArgsConstructor
public class DropsGate {

    public static final String EXPERIMENT_KEY = "drops";

    private final ExperimentService experimentService;

    /** User-facing paths (pages, public route): may assign the channel to the experiment. */
    public boolean isAvailableFor(UserAccount user) {
        return experimentService.evaluateGate(user, EXPERIMENT_KEY);
    }

    /** Background paths (the stream title): read-only, never assigns. */
    public boolean isAvailableReadOnly(UserAccount user) {
        return experimentService.isRolledOut(user, EXPERIMENT_KEY);
    }
}
