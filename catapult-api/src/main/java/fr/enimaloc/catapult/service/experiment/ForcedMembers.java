package fr.enimaloc.catapult.service.experiment;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentOverride;
import fr.enimaloc.catapult.experiment.targeting.AttributeEvaluator;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRef;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentOverrideRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Which members of an experiment a FORCE_VARIANT override pins to a variant: those targeted by a USER
 * override, plus those matched by an ATTRIBUTE override on a tag ({@code flag:<k>}) or a group.
 *
 * <p>Simplification: a member matched by any FORCE_VARIANT override counts as forced, even if a
 * higher-priority override of another kind (say a FORCE_EXCLUDE) would win for them at evaluation time.
 */
@Component
@RequiredArgsConstructor
public class ForcedMembers {

    private static final String FLAG_PREFIX = "flag:";
    private static final int CHUNK = 500;

    private final ExperimentOverrideRepository overrideRepository;
    private final ExperimentAssignmentRepository assignmentRepository;
    private final UserAccountRepository userRepository;
    private final AttributeEvaluator attributeEvaluator;

    /** Ids of the experiment's assigned members that a FORCE_VARIANT override targets. */
    public Set<UUID> userIds(Experiment experiment) {
        Set<UUID> forced = new HashSet<>();
        // A USER override pins its target whether or not they are assigned yet.
        forced.addAll(overrideRepository.findForcedUserIds(experiment));
        for (ExperimentOverride o : overrideRepository.findAttributeForceVariants(experiment)) {
            String key = o.getAttributeKey();
            String op = o.getAttributeOp();
            String val = o.getAttributeVal();
            if (key == null || op == null) continue; // nothing to match on (and no null bind)
            if (key.startsWith(FLAG_PREFIX) && key.length() > FLAG_PREFIX.length() && op.equals("exists")) {
                forced.addAll(assignmentRepository.findUserIdsWithFlag(experiment, key.substring(FLAG_PREFIX.length())));
            } else if (key.startsWith(FLAG_PREFIX) && key.length() > FLAG_PREFIX.length() && op.equals("eq")) {
                if (val != null) {
                    forced.addAll(assignmentRepository.findUserIdsWithFlagValue(
                            experiment, key.substring(FLAG_PREFIX.length()), val));
                }
            } else if (key.equals("group") && op.equals("in")) {
                if (val != null) forced.addAll(assignmentRepository.findUserIdsInGroup(experiment, val));
            } else {
                forced.addAll(evaluated(experiment, key, op, val));
            }
        }
        return forced;
    }

    /**
     * Any other key or operator can't be created from the admin UI, so there is no query shape for it:
     * run the regular evaluator over the assigned members, loading their accounts a chunk at a time.
     */
    private Set<UUID> evaluated(Experiment experiment, String key, String op, String val) {
        Set<UUID> matched = new HashSet<>();
        List<UUID> assigned = new ArrayList<>();
        for (ExperimentAssignmentRef ref : assignmentRepository.findRefsByExperiment(experiment)) {
            assigned.add(ref.userId());
        }
        for (int from = 0; from < assigned.size(); from += CHUNK) {
            List<UUID> chunk = assigned.subList(from, Math.min(assigned.size(), from + CHUNK));
            for (UserAccount user : userRepository.findAllById(chunk)) {
                if (attributeEvaluator.matches(user, key, op, val)) matched.add(user.getId());
            }
        }
        return matched;
    }
}
