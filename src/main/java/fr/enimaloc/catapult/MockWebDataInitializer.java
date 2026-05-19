package fr.enimaloc.catapult;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("mock-web")
@RequiredArgsConstructor
public class MockWebDataInitializer implements ApplicationRunner {

    private final UserAccountRepository userAccountRepository;
    private final UserSettingsRepository userSettingsRepository;

    @Override
    public void run(ApplicationArguments args) {
        UserAccount admin = createUser("mock-admin", "admin_mock");
        UserAccount user = createUser("mock-user", "user_mock");
        createSettings(admin);
        createSettings(user);
        log.info("[Mock Web] Initialized users: {} (admin), {} (user)",
                admin.getTwitchUsername(), user.getTwitchUsername());
    }

    private UserAccount createUser(String twitchId, String username) {
        return userAccountRepository.findByTwitchId(twitchId).orElseGet(() -> {
            UserAccount u = new UserAccount();
            u.setTwitchId(twitchId);
            u.setTwitchUsername(username);
            u.setStatus(UserAccount.Status.ACTIVE);
            u.setBotEnabled(true);
            return userAccountRepository.save(u);
        });
    }

    private void createSettings(UserAccount user) {
        if (!userSettingsRepository.existsById(user.getId())) {
            UserSettings settings = new UserSettings();
            settings.setUser(user);
            userSettingsRepository.save(settings);
        }
    }
}
