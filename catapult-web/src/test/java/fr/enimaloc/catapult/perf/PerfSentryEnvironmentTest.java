package fr.enimaloc.catapult.perf;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/** A perf run reports to Sentry apart from production: application-perf.properties tags it. */
class PerfSentryEnvironmentTest {

    @Configuration(proxyBeanMethods = false)
    static class Empty {}

    @Test
    void tagsPerfRunsWithThePerfEnvironment() {
        SpringApplication application = new SpringApplication(Empty.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("perf");
        try (ConfigurableApplicationContext context = application.run("--catapult.perf.enabled=true")) {
            assertThat(context.getEnvironment().getProperty("sentry.environment")).isEqualTo("perf");
            assertThat(context.getEnvironment().getProperty("sentry.dsn")).isEqualTo(System.getenv()
                    .getOrDefault("SENTRY_DSN", ""));
        }
    }
}
