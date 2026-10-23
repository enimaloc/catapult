package fr.enimaloc.catapult.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigOverrideEnvironmentPostProcessorTest {

    private StandardEnvironment envWithSources(boolean degraded) {
        var env = new StandardEnvironment();
        if (degraded) {
            env.setActiveProfiles("degraded");
        }
        env.getPropertySources().addLast(new MapPropertySource(
                "Config resource 'class path resource [application-degraded.properties]' via location 'optional:classpath:/'",
                Map.of("app.polling.interval-seconds", "60")));
        env.getPropertySources().addLast(new MapPropertySource(
                "Config resource 'class path resource [application.properties]' via location 'optional:classpath:/'",
                Map.of("app.polling.interval-seconds", "30", "steam.x", "1")));
        new ConfigOverrideEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
        var src = (DatabaseOverridePropertySource) env.getPropertySources().get(DatabaseOverridePropertySource.NAME);
        src.put("app.polling.interval-seconds", "30");
        src.put("steam.x", "2");
        return env;
    }

    @Test
    void degradedProfile_pinsTheKeysOfItsOwnFile() {
        var env = envWithSources(true);

        assertThat(env.getProperty("app.polling.interval-seconds")).isEqualTo("60");
        assertThat(env.getProperty("steam.x")).isEqualTo("2");
    }

    @Test
    void withoutTheProfile_overridesWin() {
        var env = envWithSources(false);

        assertThat(env.getProperty("app.polling.interval-seconds")).isEqualTo("30");
        assertThat(env.getProperty("steam.x")).isEqualTo("2");
    }
}
