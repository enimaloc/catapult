package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule.RuleType;

/**
 * Gate of the stream title setting (a channel-wide template and a title per game, sent with the category).
 * Enabled 10 % / control 90 %: once ACTIVE, one channel in ten gets it.
 */
@ExperimentSpec(
        key         = "stream.title",
        name        = "Titre du stream",
        description = "Change le titre du stream avec la catégorie, selon le jeu ou un modèle commun",
        variants    = {
                @ExperimentSpec.Variant(key = "control", name = "Sans", weight = 90, control = true),
                @ExperimentSpec.Variant(key = "enabled", name = "Avec", weight = 10)
        },
        rules = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100)
)
public class StreamTitleExperiment {}
