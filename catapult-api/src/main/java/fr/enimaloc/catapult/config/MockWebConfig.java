package fr.enimaloc.catapult.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("mock-web")
public class MockWebConfig {

    // IgdbService a @DependsOn("flyway") pour s'assurer que le schéma existe avant
    // son @PostConstruct. En mode mock-web, FlywayConfig est exclus et Flyway est
    // désactivé — ce bean factice satisfait la dépendance sans exécuter de migrations.
    @Bean("flyway")
    public Object mockFlyway() {
        return new Object();
    }
}
