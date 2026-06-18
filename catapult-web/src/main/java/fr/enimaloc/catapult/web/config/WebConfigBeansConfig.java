package fr.enimaloc.catapult.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

@Configuration
public class WebConfigBeansConfig {

    @Bean
    WebDatabaseOverridePropertySource webDatabaseOverridePropertySource(ConfigurableEnvironment env) {
        return (WebDatabaseOverridePropertySource) env.getPropertySources()
                .get(WebDatabaseOverridePropertySource.NAME);
    }
}
