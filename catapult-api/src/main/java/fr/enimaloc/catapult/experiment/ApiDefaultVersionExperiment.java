package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentAssignmentRule.RuleType;

/**
 * The API version a request gets when it names none (no {@code X-API-Version} header, no {@code ?version=}). ACTIVE
 * from boot: everyone lands in {@code v2} (the control, weight 100). A request without an account follows the
 * heaviest variant; a member keeps the variant they were assigned, whatever the weights become.
 *
 * <p>To move the default to version 3 without a redeploy, from the admin experiments page: set the weights to
 * {@code v2} 0 / {@code v3} 100 (requests without an account follow within a minute), then run "reassign" on the
 * experiment so that the members already enrolled in {@code v2} are drawn again and land in {@code v3}. Changing
 * the weights alone only affects members enrolled afterwards.
 */
@ExperimentSpec(
        key           = ApiDefaultVersionExperiment.KEY,
        name          = "Default API version",
        description   = "The API version served to the requests that do not name one",
        variants      = {
                @ExperimentSpec.Variant(key = ApiDefaultVersionExperiment.V2_VARIANT, name = "Version 2",
                        weight = 100, control = true),
                @ExperimentSpec.Variant(key = ApiDefaultVersionExperiment.V3_VARIANT, name = "Version 3", weight = 0)
        },
        rules         = @ExperimentSpec.Rule(type = RuleType.RANDOM, percent = 100),
        initialStatus = Experiment.Status.ACTIVE
)
public class ApiDefaultVersionExperiment {

    public static final String KEY = "api.default-version";
    public static final String V2_VARIANT = "v2";
    public static final String V3_VARIANT = "v3";
}
