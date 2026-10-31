package fr.enimaloc.catapult.simulation.account;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MockGuardTest {

    private static final String SIMULATION = "catapult.simulation.enabled=true";

    private static ApplicationContextRunner runner(String... profiles) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                .withUserConfiguration(MockGuard.class);
    }

    @Test
    void starts_with_dev_and_mock() {
        runner("dev", "mock").withPropertyValues(SIMULATION, "sentry.environment=development").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MockGuard.class);
        });
    }

    @Test
    void refuses_mock_alone() {
        runner("mock").withPropertyValues(SIMULATION).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dev,mock");
        });
    }

    @Test
    void refuses_mock_with_another_environment() {
        runner("mock").withPropertyValues(SIMULATION, "sentry.environment=beta")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void mock_guard_refuses_production() {
        runner("dev", "mock").withPropertyValues(SIMULATION, "sentry.environment=production").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("sentry.environment");
        });
    }

    @Test
    void absent_without_the_simulation() {
        runner("mock").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MockGuard.class);
        });
    }
}
