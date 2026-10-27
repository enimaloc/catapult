package fr.enimaloc.catapult.common.dto.experiment;

import java.util.UUID;

/**
 * A per-member or per-attribute override; {@code twitchUsername} is set for USER overrides,
 * {@code targetVariantId} for FORCE_VARIANT ones.
 */
public record ExperimentOverrideDto(UUID id, String type, String action, int priority,
                                    String twitchUsername, String attributeKey, String attributeOp,
                                    String attributeVal, UUID targetVariantId) {}
