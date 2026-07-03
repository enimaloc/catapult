package fr.enimaloc.catapult.experiment.targeting;

import fr.enimaloc.catapult.domain.UserAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class AttributeEvaluator {

    private final List<AttributeResolver> resolvers;
    private final OperatorEvaluator operatorEvaluator;

    public AttributeEvaluator(List<AttributeResolver> resolvers, OperatorEvaluator operatorEvaluator) {
        this.resolvers = resolvers;
        this.operatorEvaluator = operatorEvaluator;
    }

    public boolean matches(UserAccount user, String key, String operator, String expected) {
        AttributeValue value = resolvers.stream()
            .filter(r -> r.supports(key))
            .findFirst()
            .map(r -> r.resolve(user, key))
            .orElseGet(() -> {
                log.warn("No resolver for attribute key '{}', no-match", key);
                return AttributeValue.missing();
            });
        return operatorEvaluator.evaluate(value, operator, expected);
    }
}
