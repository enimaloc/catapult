package fr.enimaloc.catapult.web.service.config;

import fr.enimaloc.catapult.web.config.WebDatabaseOverridePropertySource;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class WebConfigCatalogService {

    private final ConfigurableEnvironment environment;
    private final WebConfigCatalogProperties props;

    public List<WebConfigEntry> catalog() {
        List<Pattern> secretPatterns = props.getSecretPatterns().stream().map(Pattern::compile).toList();
        Set<String> tabooKeys = Set.copyOf(props.getTabooKeys());
        List<String> exposedPrefixes = props.getExposedPrefixes();

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

        List<WebConfigEntry> out = new ArrayList<>(keys.size());
        for (String key : keys) {
            String normalized = key.replace('-', '.');
            boolean secret = secretPatterns.stream().anyMatch(p -> p.matcher(normalized).matches());
            boolean taboo = tabooKeys.contains(key);
            // catapult-web has no live-reload yet: every entry requires a restart.
            boolean restartRequired = true;
            String source = sourceOf(key);
            boolean overridden = WebDatabaseOverridePropertySource.NAME.equals(source);
            String value = secret ? null : environment.getProperty(key);
            out.add(new WebConfigEntry(key, value, secret, restartRequired, overridden, taboo, source));
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
