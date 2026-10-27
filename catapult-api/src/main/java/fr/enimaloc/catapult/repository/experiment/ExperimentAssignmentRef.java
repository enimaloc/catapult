package fr.enimaloc.catapult.repository.experiment;

import java.util.UUID;

/** A light view of an assignment (no user or variant entity loaded), for working over a whole experiment. */
public record ExperimentAssignmentRef(UUID id, UUID userId, UUID variantId) {}
