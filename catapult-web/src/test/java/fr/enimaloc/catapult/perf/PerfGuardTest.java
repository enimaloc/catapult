package fr.enimaloc.catapult.perf;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PerfGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("perf"))
            .withUserConfiguration(PerfGuard.class);

    /** The checked properties at a passing value; each test leaves out or changes the one it is about. */
    private static final String SENTRY = "sentry.environment=perf";
    private static final String ENABLED = "catapult.perf.enabled=true";

    @Test
    void refusesToStartWithoutTheProperty() {
        runner.withPropertyValues(SENTRY).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("catapult.perf.enabled=true");
        });
    }

    @Test
    void refusesToStartWhenThePropertyIsFalse() {
        runner.withPropertyValues("catapult.perf.enabled=false", SENTRY)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void startsWithTheProperty() {
        runner.withPropertyValues(ENABLED, SENTRY).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PerfGuard.class);
        });
    }

    @Test
    void refusesAnotherSentryEnvironment() {
        runner.withPropertyValues(ENABLED, "sentry.environment=production").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sentry.environment=perf")
                    .hasMessageContaining("production");
        });
    }

    @Test
    void refusesWithoutASentryEnvironment() {
        runner.withPropertyValues(ENABLED).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("sentry.environment=perf")
                    .hasMessageContaining("none");
        });
    }

    @Test
    void isAbsentOutsideThePerfProfile() {
        new ApplicationContextRunner().withUserConfiguration(PerfGuard.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(PerfGuard.class));
    }
}
