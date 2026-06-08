package fr.enimaloc.catapult.experiment.thymeleaf;

import fr.enimaloc.catapult.service.ExperimentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thymeleaf.dialect.AbstractProcessorDialect;
import org.thymeleaf.expression.IExpressionObjectFactory;
import org.thymeleaf.processor.IProcessor;
import org.thymeleaf.standard.StandardDialect;

import java.util.Set;

@Component
public class ExperimentDialect extends AbstractProcessorDialect
        implements org.thymeleaf.dialect.IExpressionObjectDialect {

    private final ExperimentService experimentService;

    @Value("${app.experiment.show-definition:false}")
    private boolean showDefinition = false;

    public ExperimentDialect(ExperimentService experimentService) {
        super("Experiment Dialect", "exp", StandardDialect.PROCESSOR_PRECEDENCE + 10);
        this.experimentService = experimentService;
    }

    @Override
    public Set<IProcessor> getProcessors(String dialectPrefix) {
        return Set.of(
            new ShowForVariantProcessor(dialectPrefix, experimentService, showDefinition),
            new HideForVariantProcessor(dialectPrefix, experimentService),
            new IfRolledOutProcessor(dialectPrefix, experimentService),
            new VariantAttributeProcessor(dialectPrefix, experimentService)
        );
    }

    @Override
    public IExpressionObjectFactory getExpressionObjectFactory() {
        return new ExperimentExpressionObjectFactory(experimentService);
    }
}
