package fr.enimaloc.catapult.repository.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.security.TwitchLoginSuccessHandler;
import fr.enimaloc.catapult.service.igdb.AdminCclService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The raid "put the last game back" delay, through the Flyway-migrated schema (same context as NotificationFlowIT). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mock-web")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:catapult_notification_flow_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE")
class UserSettingsRaidRestoreIT {

    @MockitoBean
    AdminCclService adminCclService;

    @MockitoBean
    TwitchLoginSuccessHandler twitchLoginSuccessHandler;

    @Autowired UserAccountRepository userRepo;
    @Autowired UserSettingsRepository settingsRepo;

    @Test
    void raidRestoreGameMinutes_isStoredAndOffByDefault() {
        UserSettings on = settingsFor("raid-on");
        on.setRaidRestoreGameMinutes(15);
        settingsRepo.save(on);
        UserSettings off = settingsRepo.save(settingsFor("raid-off"));

        assertThat(settingsRepo.findById(on.getUserId())).get()
                .extracting(UserSettings::getRaidRestoreGameMinutes).isEqualTo(15);
        assertThat(settingsRepo.findById(off.getUserId())).get()
                .extracting(UserSettings::getRaidRestoreGameMinutes).isNull();
    }

    private UserSettings settingsFor(String username) {
        UserAccount user = new UserAccount();
        user.setTwitchId(username + "-" + UUID.randomUUID());
        user.setTwitchUsername(username);
        user = userRepo.save(user);
        UserSettings settings = new UserSettings();
        settings.setUser(user);
        return settings;
    }
}
