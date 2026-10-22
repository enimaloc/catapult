package fr.enimaloc.catapult.common.dto.experiment;

/** Adds a rule; same JSON as the API's own request, enum names as strings. */
public record ExperimentRuleRequest(String ruleType, int priority, Integer percentage,
                                    String attributeKey, String attributeOperator, String attributeValue) {}
