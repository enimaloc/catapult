package fr.enimaloc.catapult.service.chatcommand;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The {@code chat.commands} experiment, which every chat-command route sits behind. */
@Component
@RequiredArgsConstructor
public class ChatCommandGate {

    public static final String EXPERIMENT_KEY = "chat.commands";

    private final ExperimentService experimentService;

    /** Fails with {@code FEATURE_DISABLED} unless {@code user} is rolled out to the experiment. */
    public void require(UserAccount user) {
        // evaluateGate (not isRolledOut) so that an admin override on the user
        // materialises the assignment on first call. isRolledOut only reads
        // existing assignments, so overrides without a prior assignment would
        // never let the user reach this page.
        if (!experimentService.evaluateGate(user, EXPERIMENT_KEY)) {
            throw new ApiProblem(ErrorCode.FEATURE_DISABLED, "Feature not enabled");
        }
    }
}
