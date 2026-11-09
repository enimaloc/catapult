package fr.enimaloc.catapult.common.dto.experiment;

import java.util.UUID;

/** Moves a participant to {@code variantId}, a variant of the same experiment. */
public record MoveAssignmentRequest(UUID variantId) {}
