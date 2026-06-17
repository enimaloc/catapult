package fr.enimaloc.catapult.config;

import org.junit.jupiter.api.Test;

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
}
