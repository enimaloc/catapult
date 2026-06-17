package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.ExperimentAssignmentRule.RuleType;

@ExperimentSpec(
        key         = "invite-button-placement",
        name        = "Placement du bouton Invitations",
        description = "Test A/B/C : position du CTA Invitations — nav-default (lien dans la navigation actuelle), nav-end (lien en fin de navigation), card (card dans la colonne de droite)",
        variants    = {
                @ExperimentSpec.Variant(key = "nav-default", name = "Lien de navigation (après Mes chaînes)", weight = 34, control = true),
                @ExperimentSpec.Variant(key = "nav-end",     name = "Lien de navigation (avant déconnexion)", weight = 33),
                @ExperimentSpec.Variant(key = "card",        name = "Card dans la colonne de droite",         weight = 33)
        },
        rules = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100)
)
public class InviteButtonPlacementExperiment {}
