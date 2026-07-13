package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.ExperimentAssignmentRule.RuleType;

/**
 * Gate de la feature Minecraft (liaison de compte + détection de présence).
 * Control 0 % / enabled 100 % : une fois ACTIVE, tout le monde est dans
 * enabled — l'activation en admin sert d'interrupteur de la feature.
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
