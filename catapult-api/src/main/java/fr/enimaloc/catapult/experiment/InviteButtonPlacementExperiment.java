package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.ExperimentAssignmentRule.RuleType;

@ExperimentSpec(
        key         = "invite-button-placement",
        name        = "Placement du bouton Invitations",
        description = "Test A/B/C/D : position du CTA Invitations — nav-default, nav-end, card (colonne de droite), tab (onglet page chaîne, fallback nav-default ailleurs)",
        variants    = {
                @ExperimentSpec.Variant(key = "nav-default", name = "Lien de navigation (après Mes chaînes)",       weight = 25, control = true),
                @ExperimentSpec.Variant(key = "nav-end",     name = "Lien de navigation (avant déconnexion)",       weight = 25),
                @ExperimentSpec.Variant(key = "card",        name = "Card dans la colonne de droite",               weight = 25),
                @ExperimentSpec.Variant(key = "tab",         name = "Onglet Invitations (page chaîne à onglets)",   weight = 25)
        },
        rules = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100)
)
public class InviteButtonPlacementExperiment {}
