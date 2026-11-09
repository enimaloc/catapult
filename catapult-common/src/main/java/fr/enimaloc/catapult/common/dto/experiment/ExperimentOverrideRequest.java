package fr.enimaloc.catapult.common.dto.experiment;

import java.util.UUID;

/** Adds an override; same JSON as the API's own request, enum names as strings. */
public record ExperimentOverrideRequest(String overrideType, String action, int priority, String twitchUsername,
                                        String attributeKey, String attributeOp, String attributeVal,
                                        UUID targetVariantId) {}
