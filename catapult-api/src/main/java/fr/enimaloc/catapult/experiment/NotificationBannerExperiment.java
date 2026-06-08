package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.ExperimentAssignmentRule.RuleType;
import fr.enimaloc.catapult.domain.ExperimentOverride;
import org.springframework.context.annotation.Profile;

@Profile("mock-web")
@ExperimentSpec(
        key         = "notification-banner",
        name        = "Position des notifications",
        description = "Test A/B/C : bannière en haut, en bas ou toast flottant",
        variants    = {
                @ExperimentSpec.Variant(key = "top",     name = "Haut de page",    weight = 34, control = true),
                @ExperimentSpec.Variant(key = "bottom",  name = "Bas de page",     weight = 33),
                @ExperimentSpec.Variant(key = "toast",   name = "Toast flottant",  weight = 33)
        },
        rules     = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 80),
        overrides = {
                @ExperimentSpec.Override(
                        type            = ExperimentOverride.OverrideType.USER,
                        action          = ExperimentOverride.OverrideAction.FORCE_VARIANT,
                        targetVariantId = "toast",
                        twitchUsername  = "admin_mock"),
                @ExperimentSpec.Override(
                        type            = ExperimentOverride.OverrideType.USER,
                        action          = ExperimentOverride.OverrideAction.FORCE_VARIANT,
                        targetVariantId = "bottom",
                        twitchUsername  = "user_mock_2")
        }
)
public class NotificationBannerExperiment {}
