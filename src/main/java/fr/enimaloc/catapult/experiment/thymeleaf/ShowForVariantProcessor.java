package fr.enimaloc.catapult.experiment.thymeleaf;

import fr.enimaloc.catapult.domain.ExperimentAssignment;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.templatemode.TemplateMode;

import java.util.List;

/**
 * Processes {@code exp:show-for="experimentKey:variantKey"}.
 * Removes the element entirely when the current user is not in the specified variant.
 */
class ShowForVariantProcessor extends AbstractAttributeTagProcessor {

    private static final int PRECEDENCE = 300;
    static final String ATTR_NAME = "show-for";

    ShowForVariantProcessor(String dialectPrefix) {
        super(TemplateMode.HTML, dialectPrefix, null, false, ATTR_NAME, true, PRECEDENCE, true);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void doProcess(ITemplateContext context, IProcessableElementTag tag,
                             AttributeName attributeName, String attributeValue,
                             IElementTagStructureHandler structureHandler) {
        String[] parts = attributeValue.split(":", 2);
        if (parts.length < 2) {
            return;
        }
        String experimentKey = parts[0].trim();
        String variantKey = parts[1].trim();

        List<ExperimentAssignment> assignments =
                (List<ExperimentAssignment>) context.getVariable("activeExperimentAssignments");

        boolean show = assignments != null && assignments.stream().anyMatch(a ->
                a.getExperiment().getKey().equals(experimentKey)
                        && a.getVariant().getKey().equals(variantKey));

        if (!show) {
            structureHandler.removeElement();
        }
    }
}
