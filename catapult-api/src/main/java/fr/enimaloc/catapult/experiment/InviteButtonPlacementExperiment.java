package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.ExperimentAssignmentRule.RuleType;

@ExperimentSpec(
        key         = "invite-button-placement",
        name        = "Placement du bouton Invitations",
        description = "Test A/B/C : position du bouton Invitations dans la navigation — nav-default (actuel), nav-end (fin du menu), floating (bouton flottant bas-droite)",
        variants    = {
                @ExperimentSpec.Variant(key = "nav-default", name = "Position actuelle (après Mes chaînes)", weight = 34, control = true),
                @ExperimentSpec.Variant(key = "nav-end",     name = "Fin du menu (avant déconnexion)",       weight = 33),
                @ExperimentSpec.Variant(key = "floating",    name = "Bouton flottant bas-droite",            weight = 33)
        },
        rules = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100)
)
public class InviteButtonPlacementExperiment {}
