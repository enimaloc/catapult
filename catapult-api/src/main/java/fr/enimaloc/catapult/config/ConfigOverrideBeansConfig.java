package fr.enimaloc.catapult.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

@Configuration
public class ConfigOverrideBeansConfig {

    @Bean
    DatabaseOverridePropertySource databaseOverridePropertySource(ConfigurableEnvironment env) {
        return (DatabaseOverridePropertySource) env.getPropertySources()
                .get(DatabaseOverridePropertySource.NAME);
    }
}
