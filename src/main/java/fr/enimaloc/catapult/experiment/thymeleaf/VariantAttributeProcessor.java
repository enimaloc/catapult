package fr.enimaloc.catapult.experiment.thymeleaf;

import fr.enimaloc.catapult.domain.ExperimentAssignment;
import fr.enimaloc.catapult.service.ExperimentService;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.standard.StandardDialect;
import org.thymeleaf.templatemode.TemplateMode;

import java.util.List;

class VariantAttributeProcessor extends AbstractAttributeTagProcessor {

    private static final int PRECEDENCE = StandardDialect.PROCESSOR_PRECEDENCE + 5;
    static final String ATTR_NAME = "variant";

    private final ExperimentService experimentService;

    VariantAttributeProcessor(String dialectPrefix, ExperimentService experimentService) {
        super(TemplateMode.HTML, dialectPrefix, null, false, ATTR_NAME, true, PRECEDENCE, true);
        this.experimentService = experimentService;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void doProcess(ITemplateContext context, IProcessableElementTag tag,
                             AttributeName attributeName, String attributeValue,
                             IElementTagStructureHandler structureHandler) {
        String experimentKey = attributeValue.trim();

        experimentService.ensureExists(experimentKey);

        List<ExperimentAssignment> assignments =
                (List<ExperimentAssignment>) context.getVariable("activeExperimentAssignments");

        String variantKey = assignments == null ? null : assignments.stream()
                .filter(a -> a.getExperiment().getKey().equals(experimentKey))
                .findFirst()
                .map(a -> a.getVariant().getKey())
                .orElse(null);

        structureHandler.setLocalVariable("experimentVariant", variantKey);
    }
}
