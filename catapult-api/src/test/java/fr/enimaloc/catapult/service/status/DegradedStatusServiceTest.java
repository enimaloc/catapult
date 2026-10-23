package fr.enimaloc.catapult.service.status;

import fr.enimaloc.catapult.common.dto.DegradedEffectDto;
import fr.enimaloc.catapult.common.dto.DegradedStatusDto;
import fr.enimaloc.catapult.config.DatabaseOverridePropertySource;
import fr.enimaloc.catapult.config.DegradedMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DegradedStatusServiceTest {

    // Named like the sources Spring Boot builds, which is what DegradedMode.isDegradedSource looks at.
    private static final String PROFILE_SOURCE =
            "Config resource 'class path resource [application-degraded.properties]' via location 'optional:classpath:/'";
    private static final String BASE_SOURCE =
            "Config resource 'class path resource [application.properties]' via location 'optional:classpath:/'";

    private final DatabaseOverridePropertySource overrides = new DatabaseOverridePropertySource();

    private DegradedStatusService service(boolean degraded, Map<String, Object> profile, Map<String, Object> base) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource(BASE_SOURCE, base));
        env.getPropertySources().addFirst(new MapPropertySource(PROFILE_SOURCE, profile));
        env.getPropertySources().addFirst(overrides);
        if (degraded) {
            env.setActiveProfiles(DegradedMode.PROFILE);
        }
        return new DegradedStatusService(env);
    }

    private DegradedStatusService degraded(Map<String, Object> profile, Map<String, Object> base) {
        return service(true, profile, base);
    }

    @Test
    void notDegraded_tellsNoEffects() {
        assertThat(service(false, Map.of("a", "2"), Map.of("a", "1")).status())
                .isEqualTo(new DegradedStatusDto(false, null));
    }

    @Test
    void effects_followTheProfileFileOrder() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("b", "2");
        profile.put("a", "9");

        assertThat(degraded(profile, Map.of("a", "1", "b", "1")).status().effects())
                .extracting(DegradedEffectDto::key).containsExactly("b", "a");
    }

    @Test
    void effect_carriesAppliedAndNormalValues() {
        assertThat(degraded(Map.of("app.polling.interval-seconds", "60"), Map.of("app.polling.interval-seconds", "30"))
                .status().effects())
                .containsExactly(new DegradedEffectDto("app.polling.interval-seconds", "60", "30"));
    }

    @Test
    void normalValue_ignoresSpringBootsAggregateSource() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource(BASE_SOURCE, Map.of("k", "30")));
        env.getPropertySources().addFirst(new MapPropertySource(PROFILE_SOURCE, Map.of("k", "60")));
        env.setActiveProfiles(DegradedMode.PROFILE);
        ConfigurationPropertySources.attach(env); // puts "configurationProperties" first, as a real boot does

        assertThat(new DegradedStatusService(env).status().effects())
                .containsExactly(new DegradedEffectDto("k", "60", "30"));
    }

    @Test
    void unchangedValue_hidesTheEffect() {
        assertThat(degraded(Map.of("x", "5"), Map.of("x", "5")).status().effects()).isEmpty();
    }

    @Test
    void overrideEqualToDegraded_hidesTheEffect() {
        DegradedStatusService service = degraded(Map.of("k", "60"), Map.of("k", "30"));
        overrides.put("k", "60");

        assertThat(service.status().effects()).isEmpty();
    }

    @Test
    void override_isTheNormalValue() {
        DegradedStatusService service = degraded(Map.of("k", "60"), Map.of("k", "30"));
        overrides.put("k", "45"); // loaded after the service is built, like ConfigOverrideBootstrap does

        assertThat(service.status().effects()).containsExactly(new DegradedEffectDto("k", "60", "45"));
    }

    @Test
    void pinnedOverride_isStillTheNormalValue() {
        DegradedStatusService service = degraded(Map.of("k", "60"), Map.of("k", "30"));
        overrides.put("k", "45");
        overrides.pin(Set.of("k"));

        assertThat(service.status().effects()).containsExactly(new DegradedEffectDto("k", "60", "45"));
    }

    @Test
    void normalValue_resolvesPlaceholders() {
        assertThat(degraded(Map.of("app.feedback.poll-interval-ms", "900000"),
                Map.of("app.feedback.poll-interval-ms", "${FEEDBACK_POLL_INTERVAL_MS:300000}")).status().effects())
                .containsExactly(new DegradedEffectDto("app.feedback.poll-interval-ms", "900000", "300000"));
    }

    @Test
    void disabledFeature_hidesItsEffects() {
        assertThat(degraded(Map.of("minecraft.friends-sync-interval-ms", "900000"),
                Map.of("minecraft.friends-sync-interval-ms", "300000", "minecraft.enabled", "false"))
                .status().effects()).isEmpty();
    }

    @Test
    void onlyTheFirstSegmentSwitch_hidesEffects() {
        assertThat(degraded(Map.of("app.polling.interval-seconds", "60"),
                Map.of("app.polling.interval-seconds", "30", "app.polling.enabled", "false"))
                .status().effects())
                .containsExactly(new DegradedEffectDto("app.polling.interval-seconds", "60", "30"));
    }

    @Test
    void keyWithoutNormalSource_isLeftOut() {
        assertThat(degraded(Map.of("only.in.profile", "1"), Map.of()).status().effects()).isEmpty();
    }
}
