package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.ExperimentAssignmentRule.RuleType;

@ExperimentSpec(
        key         = "sidebar-layout",
        name        = "Position du bot dans la sidebar",
        description = "Test A/B : bot en haut vs bot à gauche",
        variants    = {
                @ExperimentSpec.Variant(key = "control",     name = "Bot en haut",    weight = 50, control = true),
                @ExperimentSpec.Variant(key = "bot-left",    name = "Bot à gauche",   weight = 50)
        },
        rules       = @ExperimentSpec.Rule(type = RuleType.MANUAL)
)
public class SidebarLayoutExperiment {}
