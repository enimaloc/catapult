package fr.enimaloc.catapult.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseOverridePropertySourceTest {

    @Test
    void getProperty_returnsValueFromMap() {
        DatabaseOverridePropertySource src = new DatabaseOverridePropertySource();
        src.put("app.foo", "bar");

        assertThat(src.getProperty("app.foo")).isEqualTo("bar");
        assertThat(src.getProperty("missing")).isNull();
    }

    @Test
    void put_thenRemove_isObservable() {
        DatabaseOverridePropertySource src = new DatabaseOverridePropertySource();
        src.put("k", "v1");
        src.remove("k");

        assertThat(src.getProperty("k")).isNull();
    }

    @Test
    void name_matchesConstantUsedByPostProcessor() {
        DatabaseOverridePropertySource src = new DatabaseOverridePropertySource();
        assertThat(src.getName()).isEqualTo(DatabaseOverridePropertySource.NAME);
    }

    @Test
    void containsProperty_reflectsState() {
        DatabaseOverridePropertySource src = new DatabaseOverridePropertySource();
        src.put("k", "v");
        assertThat(src.containsProperty("k")).isTrue();
        assertThat(src.containsProperty("other")).isFalse();
    }

    @Test
    void pinnedKey_hidesItsOverride() {
        DatabaseOverridePropertySource src = new DatabaseOverridePropertySource();
        src.put("app.polling.interval-seconds", "30");
        src.pin(Set.of("app.polling.interval-seconds"));

        assertThat(src.getProperty("app.polling.interval-seconds")).isNull();
        assertThat(src.getPropertyNames()).doesNotContain("app.polling.interval-seconds");
        assertThat(src.ignores("app.polling.interval-seconds")).isTrue();
        assertThat(src.keys()).contains("app.polling.interval-seconds"); // still stored, still counted
    }

    @Test
    void unpinnedKey_keepsItsOverride() {
        DatabaseOverridePropertySource src = new DatabaseOverridePropertySource();
        src.put("app.polling.interval-seconds", "30");

        assertThat(src.getProperty("app.polling.interval-seconds")).isEqualTo("30");
        assertThat(src.ignores("app.polling.interval-seconds")).isFalse();
    }

    @Test
    void pinnedKeyWithoutOverride_isNotIgnored() {
        DatabaseOverridePropertySource src = new DatabaseOverridePropertySource();
        src.pin(Set.of("app.polling.interval-seconds"));

        assertThat(src.ignores("app.polling.interval-seconds")).isFalse();
    }
}
