package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule.RuleType;

/**
 * Gate of the legacy public API routes. ACTIVE from boot: everyone lands in {@code control}
 * (weight 100), where the legacy routes are cut. The {@code legacy} variant (weight 0) keeps the
 * old routes alive and is meant to be assigned only through an admin override.
 */
@ExperimentSpec(
        key           = LegacyApiRoutesExperiment.KEY,
        name          = "Legacy public API routes",
        description   = "Keeps the legacy public API routes alive for the accounts assigned to the legacy variant",
        variants      = {
                @ExperimentSpec.Variant(key = "control", name = "Legacy routes cut", weight = 100, control = true),
                @ExperimentSpec.Variant(key = LegacyApiRoutesExperiment.LEGACY_VARIANT,
                        name = "Legacy routes alive", weight = 0)
        },
        rules         = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100),
        initialStatus = Experiment.Status.ACTIVE
)
public class LegacyApiRoutesExperiment {

    public static final String KEY = "api.legacy-routes";
    public static final String LEGACY_VARIANT = "legacy";
}
