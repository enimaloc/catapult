package fr.enimaloc.catapult.experiment.targeting;

import java.util.Set;

public sealed interface AttributeValue
        permits AttributeValue.Number, AttributeValue.Text, AttributeValue.Multi, AttributeValue.Missing {

    record Number(double value) implements AttributeValue {}
    record Text(String value) implements AttributeValue {}
    record Multi(Set<String> values) implements AttributeValue {}
    record Missing() implements AttributeValue {}

    static AttributeValue number(double v)     { return new Number(v); }
    static AttributeValue text(String v)       { return v == null ? missing() : new Text(v); }
    static AttributeValue multi(Set<String> v) { return new Multi(v); }
    static AttributeValue missing()            { return new Missing(); }
}
