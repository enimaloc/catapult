package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule.RuleType;

/**
 * Gate of the stream title setting (a channel-wide template and a title per game, sent with the category).
 * Control 0 % / enabled 100 %: once ACTIVE everyone is in enabled — activating it in the admin is the feature's switch.
 */
@ExperimentSpec(
        key         = "stream.title",
        name        = "Titre du stream",
        description = "Change le titre du stream avec la catégorie, selon le jeu ou un modèle commun",
        variants    = {
                @ExperimentSpec.Variant(key = "control", name = "Sans", weight = 0, control = true),
                @ExperimentSpec.Variant(key = "enabled", name = "Avec", weight = 100)
        },
        rules = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100)
)
public class StreamTitleExperiment {}
