package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule.RuleType;

/**
 * Gate of the Guest Star guests ({{GUEST_STARS}}, HAS_GUEST_STARS) in stream titles, followed live, within
 * stream.title. Only for the channels stream.title enabled, one in ten of them once ACTIVE (enabled 10 % / control 90 %).
 */
@ExperimentSpec(
        key         = "stream.title.guest_stars",
        name        = "Invités Guest Star dans le titre",
        description = "Permet d'afficher les invités Guest Star dans le titre du stream",
        variants    = {
                @ExperimentSpec.Variant(key = "control", name = "Sans", weight = 90, control = true),
                @ExperimentSpec.Variant(key = "enabled", name = "Avec", weight = 10)
        },
        rules = {
                @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100),
                @ExperimentSpec.Rule(type = RuleType.EXPERIMENT, experiment = "stream.title", variant = "enabled")
        }
)
public class StreamTitleGuestStarsExperiment {}
