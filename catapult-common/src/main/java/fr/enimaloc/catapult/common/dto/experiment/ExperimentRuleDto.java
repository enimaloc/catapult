package fr.enimaloc.catapult.common.dto.experiment;

import java.util.UUID;

/** An assignment rule; {@code type} is the rule type name, the other fields apply to some types only. */
public record ExperimentRuleDto(UUID id, String type, Integer percentage,
                                String attributeKey, String attributeOperator, String attributeValue) {}
