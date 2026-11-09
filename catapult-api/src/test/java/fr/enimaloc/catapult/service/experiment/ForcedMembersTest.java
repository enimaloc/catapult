package fr.enimaloc.catapult.service.experiment;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentOverride;
import fr.enimaloc.catapult.experiment.targeting.AttributeEvaluator;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRef;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentOverrideRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForcedMembersTest {

    private final ExperimentOverrideRepository overrides = mock(ExperimentOverrideRepository.class);
    private final ExperimentAssignmentRepository assignments = mock(ExperimentAssignmentRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final AttributeEvaluator evaluator = mock(AttributeEvaluator.class);
    private final ForcedMembers forcedMembers = new ForcedMembers(overrides, assignments, users, evaluator);
    private final Experiment experiment = new Experiment();

    private ExperimentOverride attribute(String key, String op, String val) {
        ExperimentOverride o = new ExperimentOverride();
        o.setOverrideType(ExperimentOverride.OverrideType.ATTRIBUTE);
        o.setAction(ExperimentOverride.OverrideAction.FORCE_VARIANT);
        o.setAttributeKey(key);
        o.setAttributeOp(op);
        o.setAttributeVal(val);
        return o;
    }

    @Test
    void combinesUserTagAndGroupOverrides() {
        UUID user = UUID.randomUUID(), exists = UUID.randomUUID(), eq = UUID.randomUUID(), group = UUID.randomUUID();
        when(overrides.findForcedUserIds(experiment)).thenReturn(List.of(user));
        when(overrides.findAttributeForceVariants(experiment)).thenReturn(List.of(
                attribute("flag:beta", "exists", null), attribute("flag:tier", "eq", "gold"),
                attribute("group", "in", "testers")));
        when(assignments.findUserIdsWithFlag(experiment, "beta")).thenReturn(List.of(exists));
        when(assignments.findUserIdsWithFlagValue(experiment, "tier", "gold")).thenReturn(List.of(eq));
        when(assignments.findUserIdsInGroup(experiment, "testers")).thenReturn(List.of(group));

        assertThat(forcedMembers.userIds(experiment)).containsExactlyInAnyOrder(user, exists, eq, group);
    }

    @Test
    void skipsAnOverrideWhoseNeededValueIsNull() {
        when(overrides.findForcedUserIds(experiment)).thenReturn(List.of());
        when(overrides.findAttributeForceVariants(experiment)).thenReturn(List.of(
                attribute("flag:tier", "eq", null), attribute("group", "in", null), attribute(null, "in", "x")));

        assertThat(forcedMembers.userIds(experiment)).isEmpty();
        verify(assignments, never()).findUserIdsWithFlagValue(any(), anyString(), anyString());
        verify(assignments, never()).findUserIdsInGroup(any(), anyString());
    }

    @Test
    void anUnknownAttributeFallsBackToTheEvaluatorOverTheAssignedMembers() {
        UserAccount match = new UserAccount();
        match.setId(UUID.randomUUID());
        UserAccount other = new UserAccount();
        other.setId(UUID.randomUUID());
        when(overrides.findForcedUserIds(experiment)).thenReturn(List.of());
        when(overrides.findAttributeForceVariants(experiment)).thenReturn(List.of(attribute("account_age_days", "gt", "30")));
        when(assignments.findRefsByExperiment(experiment)).thenReturn(List.of(
                new ExperimentAssignmentRef(UUID.randomUUID(), match.getId(), UUID.randomUUID()),
                new ExperimentAssignmentRef(UUID.randomUUID(), other.getId(), UUID.randomUUID())));
        when(users.findAllById(any())).thenReturn(List.of(match, other));
        when(evaluator.matches(match, "account_age_days", "gt", "30")).thenReturn(true);

        assertThat(forcedMembers.userIds(experiment)).isEqualTo(Set.of(match.getId()));
    }
}
