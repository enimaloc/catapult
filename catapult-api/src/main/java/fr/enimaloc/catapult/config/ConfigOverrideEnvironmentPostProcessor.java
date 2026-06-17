package fr.enimaloc.catapult.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

public class ConfigOverrideEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        if (env.getPropertySources().contains(DatabaseOverridePropertySource.NAME)) {
            return;
        }
        env.getPropertySources().addFirst(new DatabaseOverridePropertySource());
    }
}
