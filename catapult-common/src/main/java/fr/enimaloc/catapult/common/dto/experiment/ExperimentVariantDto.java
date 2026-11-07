package fr.enimaloc.catapult.common.dto.experiment;

import java.util.UUID;

/** One arm of an experiment; {@code weight} is its share of the random split. */
public record ExperimentVariantDto(UUID id, String key, String name, int weight, boolean control) {}
