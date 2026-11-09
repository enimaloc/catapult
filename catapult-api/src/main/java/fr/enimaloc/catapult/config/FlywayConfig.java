package fr.enimaloc.catapult.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Slf4j
@Configuration
public class FlywayConfig {

    @Bean
    @ConditionalOnBooleanProperty(name = "spring.flyway.enabled", matchIfMissing = true)
    public Flyway flyway(DataSource dataSource) {
        log.info("Initializing Flyway migrations...");
        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .outOfOrder(true)
            .load();
        flyway.repair();
        flyway.migrate();
        log.info("Flyway migrations complete.");
        return flyway;
    }

    /**
     * With {@code spring.flyway.enabled=false} (the H2 test contexts, whose schema Hibernate creates), a stand-in
     * for the beans that declare {@code @DependsOn("flyway")} to start after the migrations: none run.
     */
    @Bean("flyway")
    @ConditionalOnBooleanProperty(name = "spring.flyway.enabled", havingValue = false)
    public Object noFlyway() {
        return new Object();
    }
}
