package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule.RuleType;

/**
 * Gate of the Twitch drops: the bindings' drops chip and filter, and the public /api/me/drops route.
 * Enabled 10 % / control 90 %: once ACTIVE, one channel in ten gets it.
 */
@ExperimentSpec(
        key         = "drops",
        name        = "Drops Twitch",
        description = "Montre les campagnes de drops Twitch en cours sur les jeux de la chaîne",
        variants    = {
                @ExperimentSpec.Variant(key = "control", name = "Sans", weight = 90, control = true),
                @ExperimentSpec.Variant(key = "enabled", name = "Avec", weight = 10)
        },
        rules = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100)
)
public class DropsExperiment {}
