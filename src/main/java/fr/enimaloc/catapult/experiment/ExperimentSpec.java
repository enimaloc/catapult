package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.ExperimentAssignmentRule;
import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * Declares an A/B experiment. Annotate a plain class with this and it will be
 * automatically registered as a Spring bean and synchronized to the database at
 * startup (created in DRAFT status if no record with the same key exists yet).
 *
 * <pre>{@code
 * @ExperimentSpec(
 *     key  = "new-sidebar",
 *     name = "Nouveau design sidebar",
 *     variants = {
 *         @ExperimentSpec.Variant(key = "control",      name = "Actuelle",     weight = 50, control = true),
 *         @ExperimentSpec.Variant(key = "variant-left", name = "Bot à gauche", weight = 50)
 *     },
 *     rules = @ExperimentSpec.Rule(type = RuleType.MANUAL)
 * )
 * public class SidebarExperiment {}
 * }</pre>
 *
 * In Thymeleaf, use {@code exp:show-for="key:variant"} to conditionally render
 * content based on the current user's assignment.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface ExperimentSpec {

    String key();

    String name();

    String description() default "";

    Variant[] variants();

    Rule[] rules() default {};

    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Variant {
        String key();
        String name();
        int weight() default 50;
        boolean control() default false;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Rule {
        ExperimentAssignmentRule.RuleType type() default ExperimentAssignmentRule.RuleType.RANDOM;
        int priority() default 0;
    }
}
