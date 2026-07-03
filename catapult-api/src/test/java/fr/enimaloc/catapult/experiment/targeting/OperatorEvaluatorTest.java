package fr.enimaloc.catapult.experiment.targeting;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorEvaluatorTest {

    private final OperatorEvaluator evaluator = new OperatorEvaluator();

    @Test
    void numericOperators() {
        AttributeValue five = AttributeValue.number(5);
        assertThat(evaluator.evaluate(five, "gte", "5")).isTrue();
        assertThat(evaluator.evaluate(five, ">", "4")).isTrue();
        assertThat(evaluator.evaluate(five, "lt", "5")).isFalse();
        assertThat(evaluator.evaluate(five, "eq", "5")).isTrue();
        assertThat(evaluator.evaluate(five, "neq", "5")).isFalse();
    }

    @Test
    void textOperators() {
        AttributeValue vip = AttributeValue.text("vip");
        assertThat(evaluator.evaluate(vip, "eq", "vip")).isTrue();
        assertThat(evaluator.evaluate(vip, "neq", "free")).isTrue();
        assertThat(evaluator.evaluate(vip, "contains", "vi")).isTrue();
    }

    @Test
    void multiOperators() {
        AttributeValue groups = AttributeValue.multi(Set.of("beta", "fr"));
        assertThat(evaluator.evaluate(groups, "in", "beta")).isTrue();
        assertThat(evaluator.evaluate(groups, "in", "us")).isFalse();
        assertThat(evaluator.evaluate(groups, "not_in", "us")).isTrue();
        assertThat(evaluator.evaluate(groups, "contains", "fr")).isTrue();
    }

    @Test
    void missingIsAlwaysFalse() {
        AttributeValue missing = AttributeValue.missing();
        assertThat(evaluator.evaluate(missing, "eq", "x")).isFalse();
        assertThat(evaluator.evaluate(missing, "not_in", "x")).isFalse();
    }

    @Test
    void invalidNumericExpectedIsFalse() {
        assertThat(evaluator.evaluate(AttributeValue.number(5), "gt", "abc")).isFalse();
    }
}
