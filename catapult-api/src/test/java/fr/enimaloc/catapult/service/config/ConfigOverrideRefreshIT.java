package fr.enimaloc.catapult.service.config;

import fr.enimaloc.catapult.config.DatabaseOverridePropertySource;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.TwitchLoginSuccessHandler;
import fr.enimaloc.catapult.service.AdminCclService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mock-web")
// Use a dedicated H2 in-memory DB name to avoid shared-state conflicts with other
// @SpringBootTest + @ActiveProfiles("mock-web") contexts.
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:catapult_config_refresh_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        // The test-scope application.properties does not configure the admin config catalog,
        // so we re-declare the prefixes that ConfigOverrideService.validate() relies on.
        "app.admin.config.exposed-prefixes=app.,twitch.",
        "app.admin.config.secret-patterns=.*\\.secret$,.*\\.password$",
        "app.admin.config.taboo-keys=app.owner-id,app.security.token-encryption-key"
})
@Import(ConfigOverrideRefreshIT.PropertySourceBeanConfig.class)
class ConfigOverrideRefreshIT {

    /**
     * Exposes the {@link DatabaseOverridePropertySource} that
     * {@link fr.enimaloc.catapult.config.ConfigOverrideEnvironmentPostProcessor} registered in the
     * environment as a Spring bean, so services that depend on it via constructor injection can be
     * wired in a full Spring context.
     */
    @TestConfiguration
    static class PropertySourceBeanConfig {
        @Bean
        DatabaseOverridePropertySource databaseOverridePropertySource(ConfigurableEnvironment env) {
            return (DatabaseOverridePropertySource) env.getPropertySources()
                    .get(DatabaseOverridePropertySource.NAME);
        }
    }

    @MockitoBean
    AdminCclService adminCclService;

    @MockitoBean
    TwitchLoginSuccessHandler twitchLoginSuccessHandler;

    @Autowired ConfigOverrideService overrideService;
    @Autowired ConfigurableEnvironment env;
    @Autowired UserAccountRepository userRepo;

    @Test
    void apply_thenRead_returnsNewValue() {
        UserAccount actor = userRepo.findAll().stream().findFirst().orElseGet(() -> {
            UserAccount u = new UserAccount();
            u.setTwitchId("admin-test-" + UUID.randomUUID());
            u.setTwitchUsername("admin");
            return userRepo.save(u);
        });

        overrideService.apply("app.polling.interval-seconds", "999", actor);

        assertThat(env.getProperty("app.polling.interval-seconds")).isEqualTo("999");

        overrideService.clear("app.polling.interval-seconds", actor);
    }
}
