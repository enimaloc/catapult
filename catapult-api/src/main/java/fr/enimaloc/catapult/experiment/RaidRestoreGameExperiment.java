package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule.RuleType;

/**
 * Gate of the "put the last detected game back on an outgoing raid" channel setting.
 * Control 0 % / enabled 100 %: once ACTIVE everyone is in enabled — activating it in the
 * admin is the feature's switch.
 */
@ExperimentSpec(
        key         = "raid.restore-game",
        name        = "Dernier jeu remis au raid",
        description = "Au lancement d'un raid, remet comme catégorie le dernier jeu détecté récemment",
        variants    = {
                @ExperimentSpec.Variant(key = "control", name = "Sans", weight = 0, control = true),
                @ExperimentSpec.Variant(key = "enabled", name = "Avec", weight = 100)
        },
        rules = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100)
)
public class RaidRestoreGameExperiment {}
