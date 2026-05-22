package fr.enimaloc.catapult.experiment.thymeleaf;

import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentAssignment;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.service.ExperimentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.IElementTagStructureHandler;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExperimentProcessorsTest {

    @Mock ExperimentService experimentService;
    @Mock ITemplateContext context;
    @Mock IProcessableElementTag tag;
    @Mock AttributeName attributeName;
    @Mock IElementTagStructureHandler structureHandler;

    private ExperimentAssignment greenAssignment;
    private ExperimentAssignment controlAssignment;

    @BeforeEach
    void setUp() {
        Experiment exp = new Experiment();
        exp.setKey("my-exp");

        ExperimentVariant green = new ExperimentVariant();
        green.setKey("green");
        green.setControl(false);

        ExperimentVariant control = new ExperimentVariant();
        control.setKey("control");
        control.setControl(true);

        greenAssignment = new ExperimentAssignment();
        greenAssignment.setExperiment(exp);
        greenAssignment.setVariant(green);

        controlAssignment = new ExperimentAssignment();
        controlAssignment.setExperiment(exp);
        controlAssignment.setVariant(control);
    }

    // --- HideForVariantProcessor ---

    @Test
    void hideFor_removesElement_whenUserIsInMatchingVariant() {
        when(context.getVariable("activeExperimentAssignments")).thenReturn(List.of(greenAssignment));
        HideForVariantProcessor p = new HideForVariantProcessor("exp", experimentService);
        p.doProcess(context, tag, attributeName, "my-exp:green", structureHandler);
        verify(structureHandler).removeElement();
    }

    @Test
    void hideFor_keepsElement_whenUserIsInDifferentVariant() {
        when(context.getVariable("activeExperimentAssignments")).thenReturn(List.of(greenAssignment));
        HideForVariantProcessor p = new HideForVariantProcessor("exp", experimentService);
        p.doProcess(context, tag, attributeName, "my-exp:blue", structureHandler);
        verify(structureHandler, never()).removeElement();
    }

    @Test
    void hideFor_keepsElement_whenAssignmentsAreNull() {
        when(context.getVariable("activeExperimentAssignments")).thenReturn(null);
        HideForVariantProcessor p = new HideForVariantProcessor("exp", experimentService);
        p.doProcess(context, tag, attributeName, "my-exp:green", structureHandler);
        verify(structureHandler, never()).removeElement();
    }

    @Test
    void hideFor_callsEnsureExists_withVariantHint() {
        when(context.getVariable("activeExperimentAssignments")).thenReturn(List.of());
        HideForVariantProcessor p = new HideForVariantProcessor("exp", experimentService);
        p.doProcess(context, tag, attributeName, "my-exp:green", structureHandler);
        verify(experimentService).ensureExists("my-exp", "green");
    }

    // --- ShowForVariantProcessor ---

    @Test
    void showFor_keepsElement_whenUserIsInMatchingVariant() {
        when(context.getVariable("activeExperimentAssignments")).thenReturn(List.of(greenAssignment));
        ShowForVariantProcessor p = new ShowForVariantProcessor("exp", experimentService, false);
        p.doProcess(context, tag, attributeName, "my-exp:green", structureHandler);
        verify(structureHandler, never()).removeElement();
    }

    @Test
    void showFor_removesElement_whenUserIsInDifferentVariant() {
        when(context.getVariable("activeExperimentAssignments")).thenReturn(List.of(greenAssignment));
        ShowForVariantProcessor p = new ShowForVariantProcessor("exp", experimentService, false);
        p.doProcess(context, tag, attributeName, "my-exp:blue", structureHandler);
        verify(structureHandler).removeElement();
    }

    @Test
    void showFor_removesElement_whenAssignmentsAreNull() {
        when(context.getVariable("activeExperimentAssignments")).thenReturn(null);
        ShowForVariantProcessor p = new ShowForVariantProcessor("exp", experimentService, false);
        p.doProcess(context, tag, attributeName, "my-exp:green", structureHandler);
        verify(structureHandler).removeElement();
    }

    @Test
    void showFor_callsEnsureExists_withVariantHint() {
        when(context.getVariable("activeExperimentAssignments")).thenReturn(List.of());
        ShowForVariantProcessor p = new ShowForVariantProcessor("exp", experimentService, false);
        p.doProcess(context, tag, attributeName, "my-exp:green", structureHandler);
        verify(experimentService).ensureExists("my-exp", "green");
    }

    // --- IfRolledOutProcessor ---

    @Test
    void ifRolledOut_keepsElement_whenUserHasNonControlVariant() {
        when(context.getVariable("activeExperimentAssignments")).thenReturn(List.of(greenAssignment));
        IfRolledOutProcessor p = new IfRolledOutProcessor("exp", experimentService);
        p.doProcess(context, tag, attributeName, "my-exp", structureHandler);
        verify(structureHandler, never()).removeElement();
    }

    @Test
    void ifRolledOut_removesElement_whenUserHasControlVariant() {
        when(context.getVariable("activeExperimentAssignments")).thenReturn(List.of(controlAssignment));
        IfRolledOutProcessor p = new IfRolledOutProcessor("exp", experimentService);
        p.doProcess(context, tag, attributeName, "my-exp", structureHandler);
        verify(structureHandler).removeElement();
    }

    @Test
    void ifRolledOut_removesElement_whenAssignmentsAreEmpty() {
        when(context.getVariable("activeExperimentAssignments")).thenReturn(List.of());
        IfRolledOutProcessor p = new IfRolledOutProcessor("exp", experimentService);
        p.doProcess(context, tag, attributeName, "my-exp", structureHandler);
        verify(structureHandler).removeElement();
    }

    @Test
    void ifRolledOut_callsEnsureExists_withNoVariantHints() {
        when(context.getVariable("activeExperimentAssignments")).thenReturn(List.of());
        IfRolledOutProcessor p = new IfRolledOutProcessor("exp", experimentService);
        p.doProcess(context, tag, attributeName, "my-exp", structureHandler);
        verify(experimentService).ensureExists("my-exp");
    }

    // --- VariantAttributeProcessor ---

    @Test
    void variantAttribute_setsVariantKey_whenUserIsAssigned() {
        when(context.getVariable("activeExperimentAssignments")).thenReturn(List.of(greenAssignment));
        VariantAttributeProcessor p = new VariantAttributeProcessor("exp", experimentService);
        p.doProcess(context, tag, attributeName, "my-exp", structureHandler);
        verify(structureHandler).setLocalVariable("experimentVariant", "green");
    }

    @Test
    void variantAttribute_setsNull_whenUserIsNotAssigned() {
        when(context.getVariable("activeExperimentAssignments")).thenReturn(List.of());
        VariantAttributeProcessor p = new VariantAttributeProcessor("exp", experimentService);
        p.doProcess(context, tag, attributeName, "my-exp", structureHandler);
        verify(structureHandler).setLocalVariable("experimentVariant", null);
    }

    @Test
    void variantAttribute_setsNull_whenAssignmentsAreNull() {
        when(context.getVariable("activeExperimentAssignments")).thenReturn(null);
        VariantAttributeProcessor p = new VariantAttributeProcessor("exp", experimentService);
        p.doProcess(context, tag, attributeName, "my-exp", structureHandler);
        verify(structureHandler).setLocalVariable("experimentVariant", null);
    }

    @Test
    void variantAttribute_callsEnsureExists() {
        when(context.getVariable("activeExperimentAssignments")).thenReturn(List.of());
        VariantAttributeProcessor p = new VariantAttributeProcessor("exp", experimentService);
        p.doProcess(context, tag, attributeName, "my-exp", structureHandler);
        verify(experimentService).ensureExists("my-exp");
    }
}
