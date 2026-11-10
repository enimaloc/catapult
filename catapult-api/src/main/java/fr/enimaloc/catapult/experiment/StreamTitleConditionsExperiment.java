package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule.RuleType;

/**
 * Gate of the conditions ({{COND ? [[…]] : [[…]]}}) in stream titles, within stream.title.
 * Enabled 10 % / control 90 %: once ACTIVE, one channel in ten gets it.
 */
@ExperimentSpec(
        key         = "stream.title.conditions",
        name        = "Conditions dans le titre",
        description = "Permet des conditions dans le titre du stream, comme les drops en cours",
        variants    = {
                @ExperimentSpec.Variant(key = "control", name = "Sans", weight = 90, control = true),
                @ExperimentSpec.Variant(key = "enabled", name = "Avec", weight = 10)
        },
        rules = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100)
)
public class StreamTitleConditionsExperiment {}
