package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule.RuleType;

/**
 * Gate of the Epic Games feature (account linking + presence detection).
 * Control 0 % / enabled 100 %: once ACTIVE, everyone is in
 * enabled, so activating it in the admin works as the feature's switch.
 */
@ExperimentSpec(
        key         = "epic.integration",
        name        = "Intégration Epic",
        description = "Liaison de compte Epic Games et détection de présence via les comptes de service (amis Epic)",
        variants    = {
                @ExperimentSpec.Variant(key = "control", name = "Sans Epic", weight = 0, control = true),
                @ExperimentSpec.Variant(key = "enabled", name = "Avec Epic", weight = 100)
        },
        rules = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100)
)
public class EpicIntegrationExperiment {}
