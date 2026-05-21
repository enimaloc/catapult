package fr.enimaloc.catapult.experiment.thymeleaf;

import org.springframework.stereotype.Component;
import org.thymeleaf.dialect.AbstractProcessorDialect;
import org.thymeleaf.processor.IProcessor;
import org.thymeleaf.standard.StandardDialect;

import java.util.Set;

@Component
public class ExperimentDialect extends AbstractProcessorDialect {

    public ExperimentDialect() {
        super("Experiment Dialect", "exp", StandardDialect.PROCESSOR_PRECEDENCE + 10);
    }

    @Override
    public Set<IProcessor> getProcessors(String dialectPrefix) {
        return Set.of(new ShowForVariantProcessor(dialectPrefix));
    }
}
