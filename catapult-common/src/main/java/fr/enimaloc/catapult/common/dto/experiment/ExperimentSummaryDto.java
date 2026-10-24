package fr.enimaloc.catapult.common.dto.experiment;

import java.util.List;
import java.util.UUID;

/** An experiment as listed in the admin. */
public record ExperimentSummaryDto(UUID id, String key, String name, ExperimentStatus status,
                                   List<ExperimentVariantDto> variants, int ruleCount) {}
