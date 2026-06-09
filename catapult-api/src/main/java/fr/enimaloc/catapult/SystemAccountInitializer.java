package fr.enimaloc.catapult;

import fr.enimaloc.catapult.domain.GetterConfig;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.repository.GetterConfigRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SystemAccountInitializer implements ApplicationRunner {

    private final UserAccountRepository userAccountRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final GetterConfigRepository getterConfigRepository;

    @Value("${spring.application.name:Catapult}")
    private String appName;

    @Value("${twitch.default-no-game.id:509658}")
    private String defaultNoGameId;

    @Value("${twitch.default-no-game.name:Just Chatting}")
    private String defaultNoGameName;

    @Value("${twitch.default-incomplete-game.id:66082}")
    private String defaultIncompleteGameId;

    @Value("${twitch.default-incomplete-game.name:Games + Demos}")
    private String defaultIncompleteGameName;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userAccountRepository.findBySystemAccountTrue().isPresent()) {
            log.debug("System account already exists — skipping creation");
            return;
        }

        UserAccount system = new UserAccount();
        system.setTwitchUsername(appName);
        system.setSystemAccount(true);
        system.setBotEnabled(false);
        system.setStatus(UserAccount.Status.ACTIVE);
        system = userAccountRepository.save(system);

        UserSettings settings = new UserSettings();
        settings.setUser(system);
        settings.setNoGameTwitchGameId(defaultNoGameId);
        settings.setNoGameTwitchGameName(defaultNoGameName);
        settings.setIncompleteFallbackTwitchGameId(defaultIncompleteGameId);
        settings.setIncompleteFallbackTwitchGameName(defaultIncompleteGameName);
        userSettingsRepository.save(settings);

        int priority = 1;
        for (GetterConfig.Provider provider : GetterConfig.Provider.values()) {
            GetterConfig config = new GetterConfig();
            config.setUser(system);
            config.setProvider(provider);
            config.setPriority(priority++);
            config.setEnabled(provider == GetterConfig.Provider.STEAM);
            getterConfigRepository.save(config);
        }

        log.info("System account '{}' created", appName);
    }
}
