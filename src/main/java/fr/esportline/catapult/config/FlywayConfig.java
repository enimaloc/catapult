package fr.esportline.catapult.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Configuration explicite de Flyway pour garantir l'exécution des migrations
 * indépendamment de l'auto-configuration Spring Boot.
 */
@Slf4j
@Configuration
public class FlywayConfig {

    @Bean
    @ConditionalOnMissingBean
    public Flyway flyway(DataSource dataSource) {
        log.info("Initializing Flyway migrations...");
        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load();
        flyway.migrate();
        log.info("Flyway migrations complete.");
        return flyway;
    }
}
