package fr.enimaloc.catapult.service.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ConfigCatalogServiceTest {

    private MockEnvironment env;
    private ConfigCatalogProperties props;
    private ConfigCatalogService service;

    @BeforeEach
    void setUp() {
        env = new MockEnvironment();
        env.getPropertySources().addLast(new MapPropertySource("properties", Map.of(
                "app.foo", "1",
                "app.jwt.secret", "shhh",
                "twitch.client-secret", "shhh2",
                "spring.boot", "ignored",
                "app.bar", "two"
        )));
        props = new ConfigCatalogProperties();
        props.setExposedPrefixes(List.of("app.", "twitch."));
        props.setSecretPatterns(List.of(".*\\.secret$"));
        props.setTabooKeys(List.of("app.jwt.secret"));
        props.setRestartRequiredPrefixes(List.of());
        service = new ConfigCatalogService(env, props);
    }

    @Test
    void catalog_includesOnlyExposedPrefixes() {
        List<ConfigEntry> entries = service.catalog();
        assertThat(entries).extracting(ConfigEntry::key)
                .containsExactlyInAnyOrder("app.foo", "app.bar", "app.jwt.secret", "twitch.client-secret");
    }

    @Test
    void catalog_marksSecretsAndTaboo() {
        List<ConfigEntry> entries = service.catalog();
        assertThat(entries).extracting(ConfigEntry::key, ConfigEntry::secret, ConfigEntry::taboo)
                .contains(
                        tuple("app.jwt.secret", true, true),
                        tuple("twitch.client-secret", true, false),
                        tuple("app.foo", false, false)
                );
    }

    @Test
    void catalog_secretValuesAreNotReturned() {
        List<ConfigEntry> entries = service.catalog();
        ConfigEntry secretEntry = entries.stream()
                .filter(e -> e.key().equals("twitch.client-secret"))
                .findFirst().orElseThrow();
        assertThat(secretEntry.value()).isNull();
    }
}
