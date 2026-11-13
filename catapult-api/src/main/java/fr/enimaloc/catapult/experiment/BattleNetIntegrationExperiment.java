package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule.RuleType;

/**
 * Gate of the Battle.net feature (account linking + presence detection).
 * Control 0 % / enabled 100 %: once ACTIVE, everyone is in
 * enabled, so activating it in the admin works as the feature's switch.
 */
@ExperimentSpec(
        key         = "battlenet.integration",
        name        = "Intégration Battle.net",
        description = "Liaison de compte Battle.net et détection de présence via les comptes de service (protocole BGS)",
        variants    = {
                @ExperimentSpec.Variant(key = "control", name = "Sans Battle.net", weight = 0, control = true),
                @ExperimentSpec.Variant(key = "enabled", name = "Avec Battle.net", weight = 100)
        },
        rules = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100)
)
public class BattleNetIntegrationExperiment {}
