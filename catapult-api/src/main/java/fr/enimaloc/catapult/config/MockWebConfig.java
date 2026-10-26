package fr.enimaloc.catapult.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("mock-web")
public class MockWebConfig {

    // IgdbService has @DependsOn("flyway") to make sure the schema exists before
    // its @PostConstruct. In mock-web mode, FlywayConfig is excluded and Flyway is
    // disabled — this dummy bean satisfies the dependency without running migrations.
    @Bean("flyway")
    public Object mockFlyway() {
        return new Object();
    }
}
