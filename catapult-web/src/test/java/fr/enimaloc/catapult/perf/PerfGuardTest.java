package fr.enimaloc.catapult.perf;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PerfGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("perf"))
            .withUserConfiguration(PerfGuard.class);

    @Test
    void refusesToStartWithoutTheProperty() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("catapult.perf.enabled=true");
        });
    }

    @Test
    void refusesToStartWhenThePropertyIsFalse() {
        runner.withPropertyValues("catapult.perf.enabled=false")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void startsWithTheProperty() {
        runner.withPropertyValues("catapult.perf.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PerfGuard.class);
        });
    }

    @Test
    void isAbsentOutsideThePerfProfile() {
        new ApplicationContextRunner().withUserConfiguration(PerfGuard.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(PerfGuard.class));
    }
}
