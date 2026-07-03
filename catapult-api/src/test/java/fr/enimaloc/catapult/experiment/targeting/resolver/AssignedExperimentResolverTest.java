package fr.enimaloc.catapult.experiment.targeting.resolver;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.experiment.targeting.AttributeValue;
import fr.enimaloc.catapult.repository.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.ExperimentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssignedExperimentResolverTest {

    @Mock ExperimentRepository experimentRepository;
    @Mock ExperimentAssignmentRepository assignmentRepository;
    @InjectMocks AssignedExperimentResolver resolver;

    @Test
    void resolvesAssignedVariantKey() {
        UserAccount user = new UserAccount();
        Experiment exp = new Experiment(); exp.setKey("darkmode");
        ExperimentVariant variant = new ExperimentVariant(); variant.setKey("on");
        ExperimentAssignment a = new ExperimentAssignment(); a.setVariant(variant);
        when(experimentRepository.findByKey("darkmode")).thenReturn(Optional.of(exp));
        when(assignmentRepository.findByExperimentAndUser(exp, user)).thenReturn(Optional.of(a));

        assertThat(resolver.supports("experiment:darkmode")).isTrue();
        assertThat(resolver.resolve(user, "experiment:darkmode"))
            .isEqualTo(AttributeValue.text("on"));
    }

    @Test
    void unassignedReturnsSentinel() {
        UserAccount user = new UserAccount();
        Experiment exp = new Experiment(); exp.setKey("darkmode");
        when(experimentRepository.findByKey("darkmode")).thenReturn(Optional.of(exp));
        when(assignmentRepository.findByExperimentAndUser(exp, user)).thenReturn(Optional.empty());
        assertThat(resolver.resolve(user, "experiment:darkmode"))
            .isEqualTo(AttributeValue.text("unassigned"));
    }

    @Test
    void missingExperimentReturnsMissing() {
        UserAccount user = new UserAccount();
        when(experimentRepository.findByKey("ghost")).thenReturn(Optional.empty());
        assertThat(resolver.resolve(user, "experiment:ghost"))
            .isEqualTo(AttributeValue.missing());
    }
}
