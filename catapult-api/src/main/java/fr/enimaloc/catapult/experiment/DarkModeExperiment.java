package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.ExperimentAssignmentRule.RuleType;
import fr.enimaloc.catapult.domain.ExperimentOverride;
import org.springframework.context.annotation.Profile;

@Profile("mock-web")
@ExperimentSpec(
        key         = "dark-mode-ui",
        name        = "Interface en mode sombre",
        description = "Test A/B : thème clair vs thème sombre",
        variants    = {
                @ExperimentSpec.Variant(key = "light", name = "Thème clair", weight = 50, control = true),
                @ExperimentSpec.Variant(key = "dark",  name = "Thème sombre", weight = 50)
        },
        rules     = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100),
        overrides = {
                @ExperimentSpec.Override(
                        type            = ExperimentOverride.OverrideType.USER,
                        action          = ExperimentOverride.OverrideAction.FORCE_VARIANT,
                        targetVariantId = "dark",
                        twitchUsername  = "admin_mock"),
                @ExperimentSpec.Override(
                        type            = ExperimentOverride.OverrideType.USER,
                        action          = ExperimentOverride.OverrideAction.FORCE_VARIANT,
                        targetVariantId = "light",
                        twitchUsername  = "user_mock_0"),
                @ExperimentSpec.Override(
                        type            = ExperimentOverride.OverrideType.USER,
                        action          = ExperimentOverride.OverrideAction.FORCE_VARIANT,
                        targetVariantId = "dark",
                        twitchUsername  = "user_mock_1")
        }
)
public class DarkModeExperiment {}
