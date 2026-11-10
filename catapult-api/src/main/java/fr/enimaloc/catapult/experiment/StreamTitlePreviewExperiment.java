package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule.RuleType;

/**
 * Gate of the stream title's preview under its fields, within stream.title.
 * Only for the channels stream.title enabled, one in ten of them once ACTIVE (enabled 10 % / control 90 %).
 */
@ExperimentSpec(
        key         = "stream.title.preview",
        name        = "Aperçu du titre",
        description = "Montre sous chaque champ le titre tel qu'il serait envoyé",
        variants    = {
                @ExperimentSpec.Variant(key = "control", name = "Sans", weight = 90, control = true),
                @ExperimentSpec.Variant(key = "enabled", name = "Avec", weight = 10)
        },
        rules = {
                @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100),
                @ExperimentSpec.Rule(type = RuleType.EXPERIMENT, experiment = "stream.title", variant = "enabled")
        }
)
public class StreamTitlePreviewExperiment {}
