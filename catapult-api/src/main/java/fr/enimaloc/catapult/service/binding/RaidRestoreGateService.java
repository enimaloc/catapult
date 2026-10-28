package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Whether a channel gets the raid "put the last game back" setting: experiment {@value #EXPERIMENT_KEY}. */
@Service
@RequiredArgsConstructor
public class RaidRestoreGateService {

    public static final String EXPERIMENT_KEY = "raid.restore-game";

    private final ExperimentService experimentService;

    /** User-facing paths (settings page and save): may assign the channel to the experiment. */
    public boolean isAvailableFor(UserAccount user) {
        return experimentService.evaluateGate(user, EXPERIMENT_KEY);
    }

    /** Background paths (the raid itself): read-only, never assigns. */
    public boolean isAvailableReadOnly(UserAccount user) {
        return experimentService.isRolledOut(user, EXPERIMENT_KEY);
    }
}
