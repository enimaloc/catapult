package fr.enimaloc.catapult.service.config;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.TwitchLoginSuccessHandler;
import fr.enimaloc.catapult.service.AdminCclService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mock-web")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:catapult_config_refresh_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "app.admin.config.exposed-prefixes=app.,twitch.",
        "app.admin.config.secret-patterns=.*\\.secret$,.*\\.password$",
        "app.admin.config.taboo-keys=app.owner-id,app.security.token-encryption-key"
})
class ConfigOverrideRefreshIT {

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
