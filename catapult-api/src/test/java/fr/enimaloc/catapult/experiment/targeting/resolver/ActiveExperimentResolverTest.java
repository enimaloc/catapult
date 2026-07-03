package fr.enimaloc.catapult.experiment.targeting.resolver;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.experiment.targeting.AttributeValue;
import fr.enimaloc.catapult.repository.ExperimentAssignmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActiveExperimentResolverTest {

    @Mock ExperimentAssignmentRepository assignmentRepository;
    @InjectMocks ActiveExperimentResolver resolver;

    @Test
    void countReflectsActiveAssignments() {
        UserAccount user = new UserAccount();
        when(assignmentRepository.findActiveByUser(user))
            .thenReturn(List.of(new ExperimentAssignment(), new ExperimentAssignment()));

        assertThat(resolver.resolve(user, "active_experiment_count"))
            .isEqualTo(AttributeValue.number(2));
        assertThat(resolver.resolve(user, "in_active_experiment"))
            .isEqualTo(AttributeValue.number(1));
    }

    @Test
    void inActiveExperimentIsZeroWhenNone() {
        UserAccount user = new UserAccount();
        when(assignmentRepository.findActiveByUser(user)).thenReturn(List.of());
        assertThat(resolver.resolve(user, "in_active_experiment"))
            .isEqualTo(AttributeValue.number(0));
    }
}
