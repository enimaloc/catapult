package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule.RuleType;

/**
 * Gate of the Minecraft feature (account linking + presence detection).
 * Control 0 % / enabled 100 %: once ACTIVE, everyone is in
 * enabled, so activating it in the admin works as the feature's switch.
 */
@ExperimentSpec(
        key         = "minecraft.integration",
        name        = "Intégration Minecraft",
        description = "Liaison de compte Minecraft et détection de présence via les comptes de service",
        variants    = {
                @ExperimentSpec.Variant(key = "control", name = "Sans Minecraft", weight = 0, control = true),
                @ExperimentSpec.Variant(key = "enabled", name = "Avec Minecraft", weight = 100)
        },
        rules = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100)
)
public class MinecraftIntegrationExperiment {}
