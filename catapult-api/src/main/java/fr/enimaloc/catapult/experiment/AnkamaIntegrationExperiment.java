package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule.RuleType;

/**
 * Gate of the Ankama feature (account linking + presence detection).
 * Control 0 % / enabled 100 %: once ACTIVE, everyone is in
 * enabled, so activating it in the admin works as the feature's switch.
 */
@ExperimentSpec(
        key         = "ankama.integration",
        name        = "Intégration Ankama",
        description = "Liaison de compte Ankama et détection de présence via les comptes de service (chat du launcher)",
        variants    = {
                @ExperimentSpec.Variant(key = "control", name = "Sans Ankama", weight = 0, control = true),
                @ExperimentSpec.Variant(key = "enabled", name = "Avec Ankama", weight = 100)
        },
        rules = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100)
)
public class AnkamaIntegrationExperiment {}
