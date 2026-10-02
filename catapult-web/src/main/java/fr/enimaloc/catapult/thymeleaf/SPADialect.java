package fr.enimaloc.catapult.thymeleaf;

import org.springframework.stereotype.Component;
import org.thymeleaf.dialect.AbstractProcessorDialect;
import org.thymeleaf.processor.IProcessor;

import java.util.Set;

@Component
public class SPADialect extends AbstractProcessorDialect {

    public static final String PREFIX = "spa";
    private static final int PROCESSOR_PRECEDENCE = 1000;

    public SPADialect() {
        super("SPA", PREFIX, PROCESSOR_PRECEDENCE);
    }

    @Override
    public Set<IProcessor> getProcessors(String dialectPrefix) {
        return Set.of(
                new IfAttributeProcessor(dialectPrefix),
                new OnAttributeProcessor(dialectPrefix),
                new ValueAttributeProcessor(dialectPrefix));
    }
}
