package fr.enimaloc.catapult.web.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

public class WebConfigOverrideEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        if (env.getPropertySources().contains(WebDatabaseOverridePropertySource.NAME)) {
            return;
        }
        env.getPropertySources().addFirst(new WebDatabaseOverridePropertySource());
    }
}
