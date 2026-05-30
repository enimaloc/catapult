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

class HideForVariantProcessor extends AbstractAttributeTagProcessor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HideForVariantProcessor.class);
    private static final int PRECEDENCE = StandardDialect.PROCESSOR_PRECEDENCE + 10;
    static final String ATTR_NAME = "hide-for";

    private final ExperimentService experimentService;

    HideForVariantProcessor(String dialectPrefix, ExperimentService experimentService) {
        super(TemplateMode.HTML, dialectPrefix, null, false, ATTR_NAME, true, PRECEDENCE, true);
        this.experimentService = experimentService;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void doProcess(ITemplateContext context, IProcessableElementTag tag,
                             AttributeName attributeName, String attributeValue,
                             IElementTagStructureHandler structureHandler) {
        String[] parts = attributeValue.split(":", 2);
        if (parts.length < 2) {
            log.warn("exp:{} attribute '{}' is missing the ':variantKey' part — element will be kept", ATTR_NAME, attributeValue);
            return;
        }
        String experimentKey = parts[0].trim();
        String variantKey = parts[1].trim();

        experimentService.ensureExists(experimentKey, variantKey);

        List<ExperimentAssignment> assignments =
                (List<ExperimentAssignment>) context.getVariable("activeExperimentAssignments");

        if (assignments == null) {
            structureHandler.removeElement();
            return;
        }

        boolean hide = assignments.stream().anyMatch(a ->
                a.getExperiment().getKey().equals(experimentKey)
                        && a.getVariant().getKey().equals(variantKey));

        if (hide) {
            structureHandler.removeElement();
        }
    }
}
