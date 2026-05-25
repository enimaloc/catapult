package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.ExperimentAssignmentRule.RuleType;
import fr.enimaloc.catapult.domain.ExperimentOverride;
import org.springframework.context.annotation.Profile;

@Profile("mock-web")
@ExperimentSpec(
        key         = "sidebar-layout",
        name        = "Position du bot dans la sidebar",
        description = "Test A/B : bot en haut vs bot à gauche",
        variants    = {
                @ExperimentSpec.Variant(key = "control",     name = "Bot en haut",    weight = 34, control = true),
                @ExperimentSpec.Variant(key = "bot-left",    name = "Bot à gauche",   weight = 33),
                @ExperimentSpec.Variant(key = "bot-right",    name = "Bot à droite",   weight = 33)
        },
        rules       = @ExperimentSpec.Rule(type = RuleType.RANDOM),
        overrides   = {
                @ExperimentSpec.Override(
                type = ExperimentOverride.OverrideType.USER,
                action = ExperimentOverride.OverrideAction.FORCE_VARIANT,
                targetVariantId = "control",
                twitchUsername = "admin_mock"),
                @ExperimentSpec.Override(
                type = ExperimentOverride.OverrideType.USER,
                action = ExperimentOverride.OverrideAction.FORCE_VARIANT,
                targetVariantId = "bot-left",
                twitchUsername = "user_mock_0"),
                @ExperimentSpec.Override(
                type = ExperimentOverride.OverrideType.USER,
                action = ExperimentOverride.OverrideAction.FORCE_VARIANT,
                targetVariantId = "control",
                twitchUsername = "user_mock_1"),
                @ExperimentSpec.Override(
                type = ExperimentOverride.OverrideType.USER,
                action = ExperimentOverride.OverrideAction.FORCE_VARIANT,
                targetVariantId = "bot-right",
                twitchUsername = "user_mock_2"),
        }
)
public class SidebarLayoutExperiment {}
