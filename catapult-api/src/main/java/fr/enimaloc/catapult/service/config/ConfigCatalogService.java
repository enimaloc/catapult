package fr.enimaloc.catapult.service.config;

import fr.enimaloc.catapult.config.DatabaseOverridePropertySource;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ConfigCatalogService {

    private final ConfigurableEnvironment environment;
    private final ConfigCatalogProperties props;

    public List<ConfigEntry> catalog() {
        List<Pattern> secretPatterns = props.getSecretPatterns().stream().map(Pattern::compile).toList();
        Set<String> tabooKeys = Set.copyOf(props.getTabooKeys());
        List<String> exposedPrefixes = props.getExposedPrefixes();
        List<String> restartPrefixes = props.getRestartRequiredPrefixes();

        Set<String> keys = new TreeSet<>();
        for (PropertySource<?> ps : environment.getPropertySources()) {
            if (ps instanceof EnumerablePropertySource<?> eps) {
                for (String name : eps.getPropertyNames()) {
                    if (exposedPrefixes.stream().anyMatch(name::startsWith)) {
                        keys.add(name);
                    }
                }
            }
        }

        List<ConfigEntry> out = new ArrayList<>(keys.size());
        for (String key : keys) {
            String normalized = key.replace('-', '.');
            boolean secret = secretPatterns.stream().anyMatch(p -> p.matcher(normalized).matches());
            boolean taboo = tabooKeys.contains(key);
            boolean restartRequired = restartPrefixes.stream().anyMatch(key::startsWith);
            String source = sourceOf(key);
            boolean overridden = DatabaseOverridePropertySource.NAME.equals(source);
            String value = secret ? null : environment.getProperty(key);
            out.add(new ConfigEntry(key, value, secret, restartRequired, overridden, taboo, source));
        }
        return out;
    }

    private String sourceOf(String key) {
        for (PropertySource<?> ps : environment.getPropertySources()) {
            if (ps.containsProperty(key)) {
                return ps.getName();
            }
        }
        return "unknown";
    }
}
