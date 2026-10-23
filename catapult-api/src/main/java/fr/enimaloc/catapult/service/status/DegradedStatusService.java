package fr.enimaloc.catapult.service.status;

import fr.enimaloc.catapult.common.dto.DegradedEffectDto;
import fr.enimaloc.catapult.common.dto.DegradedStatusDto;
import fr.enimaloc.catapult.config.DatabaseOverridePropertySource;
import fr.enimaloc.catapult.config.DegradedMode;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DegradedStatusService {

    private final ConfigurableEnvironment env;
    private final boolean degraded;
    // Key -> value applied by the profile, captured at construction like the @Scheduled annotations they feed.
    private final Map<String, String> applied = new LinkedHashMap<>();

    public DegradedStatusService(ConfigurableEnvironment env) {
        this.env = env;
        this.degraded = env.matchesProfiles(DegradedMode.PROFILE);
        if (degraded) {
            for (PropertySource<?> source : env.getPropertySources()) {
                if (source instanceof EnumerablePropertySource<?> enumerable && DegradedMode.isDegradedSource(source.getName())) {
                    for (String key : enumerable.getPropertyNames()) {
                        applied.computeIfAbsent(key, env::getProperty);
                    }
                }
            }
        }
    }

    public DegradedStatusDto status() {
        if (!degraded) {
            return new DegradedStatusDto(false, null);
        }
        // Computed on each call: the database overrides are loaded once the application is ready.
        List<DegradedEffectDto> effects = new ArrayList<>();
        applied.forEach((key, value) -> {
            if (featureDisabled(key)) {
                return;
            }
            normalValue(key)
                    .filter(normal -> !normal.equals(value))
                    .ifPresent(normal -> effects.add(new DegradedEffectDto(key, value, normal)));
        });
        return new DegradedStatusDto(true, effects);
    }

    /** What the key would be without the profile: the stored override, else the first regular source. */
    private Optional<String> normalValue(String key) {
        Optional<String> override = env.getPropertySources().stream()
                .filter(DatabaseOverridePropertySource.class::isInstance)
                .map(DatabaseOverridePropertySource.class::cast)
                .findFirst()
                .flatMap(source -> source.override(key));
        if (override.isPresent()) {
            return override.map(env::resolvePlaceholders);
        }
        for (PropertySource<?> source : env.getPropertySources()) {
            // Only concrete origins count: Boot's aggregate source (not enumerable) sits first and
            // delegates to every other source, the degraded one included.
            if (!(source instanceof EnumerablePropertySource<?>)
                    || DatabaseOverridePropertySource.NAME.equals(source.getName())
                    || DegradedMode.isDegradedSource(source.getName())) {
                continue;
            }
            Object raw = source.getProperty(key);
            if (raw != null) {
                return Optional.of(env.resolvePlaceholders(raw.toString()));
            }
        }
        return Optional.empty();
    }

    /** True when the "<first segment>.enabled" switch is off, e.g. minecraft.enabled for minecraft.*. */
    private boolean featureDisabled(String key) {
        int dot = key.indexOf('.');
        return dot > 0 && "false".equalsIgnoreCase(env.getProperty(key.substring(0, dot) + ".enabled"));
    }
}
