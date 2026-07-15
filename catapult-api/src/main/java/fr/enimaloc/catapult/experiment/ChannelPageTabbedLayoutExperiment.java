package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.ExperimentAssignmentRule.RuleType;

/**
 * Test A/B 50/50 : nouveau layout de la page chaîne à onglets
 * (Dashboard / Configuration / Commandes) contre le layout actuel en
 * défilement unique. Résolu uniquement côté propriétaire de la chaîne —
 * les visiteurs ne sont jamais assignés.
 */
@ExperimentSpec(
        key         = "channel-page-tabbed-layout",
        name        = "Layout à onglets de la page chaîne",
        description = "Test A/B : layout actuel en défilement unique (control) contre layout à onglets Dashboard/Configuration/Commandes (tabbed)",
        variants    = {
                @ExperimentSpec.Variant(key = "control", name = "Défilement unique (actuel)", weight = 50, control = true),
                @ExperimentSpec.Variant(key = "tabbed",  name = "Onglets Dashboard/Configuration/Commandes", weight = 50)
        },
        rules = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100)
)
public class ChannelPageTabbedLayoutExperiment {}
