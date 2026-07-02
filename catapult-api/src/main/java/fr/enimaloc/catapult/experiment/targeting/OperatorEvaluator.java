package fr.enimaloc.catapult.experiment.targeting;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OperatorEvaluator {

    public boolean evaluate(AttributeValue actual, String operator, String expected) {
        return switch (actual) {
            case AttributeValue.Missing ignored -> false;
            case AttributeValue.Number n        -> numeric(n.value(), operator, expected);
            case AttributeValue.Text t          -> text(t.value(), operator, expected);
            case AttributeValue.Multi m         -> multi(m.values(), operator, expected);
        };
    }

    private boolean numeric(double actual, String op, String expected) {
        double e;
        try {
            e = Double.parseDouble(expected);
        } catch (NumberFormatException ex) {
            log.warn("Invalid numeric expected '{}', no-match", expected);
            return false;
        }
        return switch (op) {
            case "eq",  "==" -> actual == e;
            case "neq", "!=" -> actual != e;
            case "gt",  ">"  -> actual >  e;
            case "gte", ">=" -> actual >= e;
            case "lt",  "<"  -> actual <  e;
            case "lte", "<=" -> actual <= e;
            default          -> false;
        };
    }

    private boolean text(String actual, String op, String expected) {
        return switch (op) {
            case "eq",  "==" -> actual.equals(expected);
            case "neq", "!=" -> !actual.equals(expected);
            case "contains"  -> expected != null && actual.contains(expected);
            default          -> false;
        };
    }

    private boolean multi(java.util.Set<String> actual, String op, String expected) {
        return switch (op) {
            case "in", "contains" -> actual.contains(expected);
            case "not_in"         -> !actual.contains(expected);
            default               -> false;
        };
    }
}
