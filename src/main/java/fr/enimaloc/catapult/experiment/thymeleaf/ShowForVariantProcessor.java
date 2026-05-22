package fr.enimaloc.catapult.experiment.thymeleaf;

import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentAssignment;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.ExperimentService;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.standard.StandardDialect;
import org.thymeleaf.templatemode.TemplateMode;

import java.util.List;
import java.util.Optional;

class ShowForVariantProcessor extends AbstractAttributeTagProcessor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ShowForVariantProcessor.class);
    private static final int PRECEDENCE = StandardDialect.PROCESSOR_PRECEDENCE + 10;
    static final String ATTR_NAME = "show-for";

    private final ExperimentService experimentService;

    ShowForVariantProcessor(String dialectPrefix, ExperimentService experimentService, boolean showDefinition) {
        super(TemplateMode.HTML, dialectPrefix, null, false, ATTR_NAME, true, PRECEDENCE, !showDefinition);
        this.experimentService = experimentService;
    }

    @Override
    @Transactional
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
        Optional<Experiment> experiment = experimentService.getExperiment(experimentKey);

        List<ExperimentAssignment> assignments =
                (List<ExperimentAssignment>) context.getVariable("activeExperimentAssignments");
        UserAccount userAccount = (UserAccount) context.getVariable("user");

        Experiment.Status status = experiment.map(Experiment::getStatus).orElse(Experiment.Status.DRAFT);

        boolean show;
        if (status == Experiment.Status.DRAFT) {
            show = variantKey.equals("control");
        } else if (status == Experiment.Status.ENDED || status == Experiment.Status.PAUSED) {
            Optional<ExperimentVariant> assigned = experimentService.getAssignedVariant(userAccount, experiment.get());
            show = assigned.map(v -> v.getKey().equals(variantKey)).orElseGet(() -> variantKey.equals("control"));
        } else {
            // ACTIVE: use cached assignments list when available, fall back to getVariant
            // to ensure assignment is created for first-time users
            boolean hasAssignment = assignments != null && assignments.stream()
                    .anyMatch(a -> a.getExperiment().getKey().equals(experimentKey));
            if (hasAssignment) {
                show = assignments.stream().anyMatch(a ->
                        a.getExperiment().getKey().equals(experimentKey)
                                && a.getVariant().getKey().equals(variantKey));
            } else {
                Optional<ExperimentVariant> variant = experimentService.getVariant(userAccount, experimentKey);
                show = variant.map(v -> v.getKey().equals(variantKey)).orElseGet(() -> variantKey.equals("control"));
            }
        }

        if (!show) {
            structureHandler.removeElement();
        }
    }
}
