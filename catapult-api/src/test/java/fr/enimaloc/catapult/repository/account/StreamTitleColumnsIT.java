package fr.enimaloc.catapult.repository.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
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

/** The stream title columns, through the Flyway-migrated schema (same context as NotificationFlowIT). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("h2-test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:catapult_notification_flow_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE")
class StreamTitleColumnsIT {

    @MockitoBean
    AdminCclService adminCclService;

    @MockitoBean
    TwitchLoginSuccessHandler twitchLoginSuccessHandler;

    @Autowired UserAccountRepository userRepo;
    @Autowired UserSettingsRepository settingsRepo;
    @Autowired GameBindingRepository bindingRepo;

    @Test
    void theChannelWideTitle_isStoredAndOffByDefault() {
        UserSettings on = new UserSettings();
        on.setUser(user("title-on"));
        on.setTitleFeatureEnabled(true);
        on.setTitleTemplate("Playing {game}");
        settingsRepo.save(on);
        UserSettings off = new UserSettings();
        off.setUser(user("title-off"));
        settingsRepo.save(off);

        assertThat(settingsRepo.findById(on.getUserId())).get().satisfies(s -> {
            assertThat(s.isTitleFeatureEnabled()).isTrue();
            assertThat(s.getTitleTemplate()).isEqualTo("Playing {game}");
        });
        assertThat(settingsRepo.findById(off.getUserId())).get().satisfies(s -> {
            assertThat(s.isTitleFeatureEnabled()).isFalse();
            assertThat(s.getTitleTemplate()).isNull();
        });
    }

    @Test
    void aGamesTitle_isStored() {
        GameBinding binding = new GameBinding();
        binding.setUser(user("title-binding"));
        binding.setSourceType(GameBinding.SourceType.STEAM);
        binding.setSourceId("730");
        binding.setSourceName("Counter-Strike 2");
        binding.setStatus(GameBinding.Status.AUTO);
        binding.setTitle("{game} ranked grind");
        binding = bindingRepo.save(binding);

        assertThat(bindingRepo.findById(binding.getId())).get()
                .extracting(GameBinding::getTitle).isEqualTo("{game} ranked grind");
    }

    private UserAccount user(String username) {
        UserAccount user = new UserAccount();
        user.setTwitchId(username + "-" + UUID.randomUUID());
        user.setTwitchUsername(username);
        return userRepo.save(user);
    }
}
