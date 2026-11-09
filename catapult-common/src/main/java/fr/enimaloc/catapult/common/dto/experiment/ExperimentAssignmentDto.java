package fr.enimaloc.catapult.common.dto.experiment;

import java.time.Instant;
import java.util.UUID;

/** A member assigned to a variant; forced when a USER FORCE_VARIANT override of this experiment targets them. */
public record ExperimentAssignmentDto(UUID userId, String twitchUsername, UUID variantId, Instant assignedAt,
                                      boolean forced) {}
