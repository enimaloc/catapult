package fr.enimaloc.catapult.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.util.Arrays;
import java.util.stream.Collectors;

public class ConfigOverrideEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        if (!env.getPropertySources().contains(DatabaseOverridePropertySource.NAME)) {
            env.getPropertySources().addFirst(new DatabaseOverridePropertySource());
        }
        if (env.matchesProfiles(DegradedMode.PROFILE)) {
            pinDegradedKeys(env, (DatabaseOverridePropertySource) env.getPropertySources().get(DatabaseOverridePropertySource.NAME));
        }
    }

    // The degraded profile's own keys must beat whatever the admin stored in the database.
    private void pinDegradedKeys(ConfigurableEnvironment env, DatabaseOverridePropertySource overrides) {
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (ps instanceof EnumerablePropertySource<?> eps && DegradedMode.isDegradedSource(eps.getName())) {
                overrides.pin(Arrays.stream(eps.getPropertyNames()).collect(Collectors.toSet()));
            }
        }
    }
}
