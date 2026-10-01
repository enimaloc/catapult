package fr.enimaloc.catapult.thymeleaf;

import org.springframework.stereotype.Component;
import org.thymeleaf.dialect.AbstractProcessorDialect;
import org.thymeleaf.processor.IProcessor;

import java.util.Set;

@Component
public class VisibilityDialect extends AbstractProcessorDialect {

    public static final String PREFIX = "sp";
    private static final int PROCESSOR_PRECEDENCE = 1000;

    public VisibilityDialect() {
        super("Visibility", PREFIX, PROCESSOR_PRECEDENCE);
    }

    @Override
    public Set<IProcessor> getProcessors(String dialectPrefix) {
        return Set.of(new VisibleWhenAttributeProcessor(dialectPrefix));
    }
}
