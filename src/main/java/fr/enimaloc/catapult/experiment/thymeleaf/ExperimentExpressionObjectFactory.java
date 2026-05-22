package fr.enimaloc.catapult.experiment.thymeleaf;

import fr.enimaloc.catapult.service.ExperimentService;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.expression.IExpressionObjectFactory;

import java.util.Set;

class ExperimentExpressionObjectFactory implements IExpressionObjectFactory {

    ExperimentExpressionObjectFactory(ExperimentService s) {}

    public Set<String> getAllExpressionObjectNames() { return Set.of(); }

    public boolean isCacheable(String name) { return false; }

    public Object buildObject(IExpressionContext ctx, String name) { return null; }
}
