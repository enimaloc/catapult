package fr.enimaloc.catapult.service.config;

import fr.enimaloc.catapult.config.DatabaseOverridePropertySource;
import fr.enimaloc.catapult.domain.ConfigOverride;
import fr.enimaloc.catapult.repository.ConfigOverrideRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigOverrideBootstrapTest {

    @Mock ConfigOverrideRepository repo;

    @Test
    void loadAll_copiesOverridesIntoSource() {
        DatabaseOverridePropertySource src = new DatabaseOverridePropertySource();
        ConfigOverride a = override("app.x", "1");
        ConfigOverride b = override("twitch.y", "2");
        when(repo.findAll()).thenReturn(List.of(a, b));

        new ConfigOverrideBootstrap(repo, src, null).loadAll();

        assertThat(src.getProperty("app.x")).isEqualTo("1");
        assertThat(src.getProperty("twitch.y")).isEqualTo("2");
    }

    private static ConfigOverride override(String key, String value) {
        ConfigOverride o = new ConfigOverride();
        o.setKey(key);
        o.setValue(value);
        return o;
    }
}
