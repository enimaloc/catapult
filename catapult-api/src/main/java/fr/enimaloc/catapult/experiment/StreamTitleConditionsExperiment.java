package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule.RuleType;

/**
 * Gate of the conditions ({{COND ? [[…]] : [[…]]}}) in stream titles, within stream.title.
 * Only for the channels stream.title enabled, one in ten of them once ACTIVE (enabled 10 % / control 90 %).
 */
@ExperimentSpec(
        key         = "stream.title.conditions",
        name        = "Conditions dans le titre",
        description = "Permet des conditions dans le titre du stream, comme les drops en cours",
        variants    = {
                @ExperimentSpec.Variant(key = "control", name = "Sans", weight = 90, control = true),
                @ExperimentSpec.Variant(key = "enabled", name = "Avec", weight = 10)
        },
        rules = {
                @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100),
                @ExperimentSpec.Rule(type = RuleType.EXPERIMENT, experiment = "stream.title", variant = "enabled")
        }
)
public class StreamTitleConditionsExperiment {}
