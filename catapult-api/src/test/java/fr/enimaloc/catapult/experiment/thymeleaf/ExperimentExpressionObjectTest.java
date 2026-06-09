package fr.enimaloc.catapult.experiment.thymeleaf;

import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentAssignment;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.ExperimentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExperimentExpressionObjectTest {

    @Mock ExperimentService experimentService;

    private UserAccount user;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    private ExperimentAssignment assignment(String expKey, String variantKey, boolean isControl) {
        Experiment exp = new Experiment();
        exp.setKey(expKey);
        ExperimentVariant v = new ExperimentVariant();
        v.setKey(variantKey);
        v.setControl(isControl);
        ExperimentAssignment a = new ExperimentAssignment();
        a.setExperiment(exp);
        a.setVariant(v);
        return a;
    }

    @Test
    void getAll_returnsActiveAssignments() {
        List<ExperimentAssignment> expected = List.of(assignment("exp", "green", false));
        when(experimentService.getActiveAssignments(user)).thenReturn(expected);
        ExperimentExpressionObject obj = new ExperimentExpressionObject(experimentService, user);
        assertThat(obj.getAll()).isEqualTo(expected);
    }

    @Test
    void getVariant_returnsVariantKey_whenAssigned() {
        when(experimentService.getActiveAssignments(user))
            .thenReturn(List.of(assignment("my-exp", "green", false)));
        ExperimentExpressionObject obj = new ExperimentExpressionObject(experimentService, user);
        assertThat(obj.getVariant("my-exp")).isEqualTo("green");
        verify(experimentService).ensureExists("my-exp");
    }

    @Test
    void getVariant_returnsNull_whenNotAssigned() {
        when(experimentService.getActiveAssignments(user)).thenReturn(List.of());
        ExperimentExpressionObject obj = new ExperimentExpressionObject(experimentService, user);
        assertThat(obj.getVariant("unknown")).isNull();
    }

    @Test
    void isInVariant_returnsTrue_whenVariantMatches() {
        when(experimentService.getActiveAssignments(user))
            .thenReturn(List.of(assignment("my-exp", "green", false)));
        ExperimentExpressionObject obj = new ExperimentExpressionObject(experimentService, user);
        assertThat(obj.isInVariant("my-exp", "green")).isTrue();
        verify(experimentService).ensureExists("my-exp", "green");
    }

    @Test
    void isInVariant_returnsFalse_whenVariantDiffers() {
        when(experimentService.getActiveAssignments(user))
            .thenReturn(List.of(assignment("my-exp", "green", false)));
        ExperimentExpressionObject obj = new ExperimentExpressionObject(experimentService, user);
        assertThat(obj.isInVariant("my-exp", "blue")).isFalse();
    }

    @Test
    void isRolledOut_returnsTrue_whenNonControlVariant() {
        when(experimentService.getActiveAssignments(user))
            .thenReturn(List.of(assignment("my-exp", "green", false)));
        ExperimentExpressionObject obj = new ExperimentExpressionObject(experimentService, user);
        assertThat(obj.isRolledOut("my-exp")).isTrue();
        verify(experimentService).ensureExists("my-exp");
    }

    @Test
    void isRolledOut_returnsFalse_whenControlVariant() {
        when(experimentService.getActiveAssignments(user))
            .thenReturn(List.of(assignment("my-exp", "control", true)));
        ExperimentExpressionObject obj = new ExperimentExpressionObject(experimentService, user);
        assertThat(obj.isRolledOut("my-exp")).isFalse();
    }

    @Test
    void getAll_returnsEmpty_forAnonymousUser() {
        ExperimentExpressionObject obj = new ExperimentExpressionObject(experimentService, null);
        assertThat(obj.getAll()).isEmpty();
        verifyNoInteractions(experimentService);
    }

    @Test
    void getVariant_returnsNull_forAnonymousUser() {
        ExperimentExpressionObject obj = new ExperimentExpressionObject(experimentService, null);
        assertThat(obj.getVariant("k")).isNull();
        verifyNoInteractions(experimentService);
    }

    @Test
    void isInVariant_returnsFalse_forAnonymousUser() {
        ExperimentExpressionObject obj = new ExperimentExpressionObject(experimentService, null);
        assertThat(obj.isInVariant("k", "v")).isFalse();
        verifyNoInteractions(experimentService);
    }

    @Test
    void isRolledOut_returnsFalse_forAnonymousUser() {
        ExperimentExpressionObject obj = new ExperimentExpressionObject(experimentService, null);
        assertThat(obj.isRolledOut("k")).isFalse();
        verifyNoInteractions(experimentService);
    }

    @Test
    void assignmentsCachedAcrossMultipleCalls() {
        when(experimentService.getActiveAssignments(user)).thenReturn(List.of());
        ExperimentExpressionObject obj = new ExperimentExpressionObject(experimentService, user);
        obj.getAll();
        obj.getVariant("x");
        obj.isRolledOut("y");
        verify(experimentService, times(1)).getActiveAssignments(user);
    }
}
