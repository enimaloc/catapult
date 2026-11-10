package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule.RuleType;

/**
 * Gate of the stream title's preview under its fields, within stream.title.
 * Enabled 10 % / control 90 %: once ACTIVE, one channel in ten gets it.
 */
@ExperimentSpec(
        key         = "stream.title.preview",
        name        = "Aperçu du titre",
        description = "Montre sous chaque champ le titre tel qu'il serait envoyé",
        variants    = {
                @ExperimentSpec.Variant(key = "control", name = "Sans", weight = 90, control = true),
                @ExperimentSpec.Variant(key = "enabled", name = "Avec", weight = 10)
        },
        rules = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100)
)
public class StreamTitlePreviewExperiment {}
