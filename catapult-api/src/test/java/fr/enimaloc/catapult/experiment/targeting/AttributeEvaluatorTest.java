package fr.enimaloc.catapult.experiment.targeting;

import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeEvaluatorTest {

    private final AttributeResolver constantVip = new AttributeResolver() {
        @Override public boolean supports(String key) { return key.equals("tier"); }
        @Override public AttributeValue resolve(UserAccount u, String k) { return AttributeValue.text("vip"); }
    };

    private final AttributeEvaluator evaluator =
        new AttributeEvaluator(List.of(constantVip), new OperatorEvaluator());

    @Test
    void delegatesToSupportingResolver() {
        assertThat(evaluator.matches(new UserAccount(), "tier", "eq", "vip")).isTrue();
        assertThat(evaluator.matches(new UserAccount(), "tier", "eq", "free")).isFalse();
    }

    @Test
    void unknownKeyIsFalse() {
        assertThat(evaluator.matches(new UserAccount(), "unknown", "eq", "x")).isFalse();
    }
}
