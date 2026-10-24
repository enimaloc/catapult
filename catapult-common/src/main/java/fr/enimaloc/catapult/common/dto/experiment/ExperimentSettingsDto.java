package fr.enimaloc.catapult.common.dto.experiment;

import java.util.List;
import java.util.UUID;

/** Everything the admin edits on one experiment: variants, rules and overrides. */
public record ExperimentSettingsDto(UUID id, String key, String name, String description,
                                    ExperimentStatus status, List<ExperimentVariantDto> variants,
                                    List<ExperimentRuleDto> rules, List<ExperimentOverrideDto> overrides) {}
