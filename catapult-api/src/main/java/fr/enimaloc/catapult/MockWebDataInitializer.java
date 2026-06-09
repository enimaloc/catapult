package fr.enimaloc.catapult;

import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentAssignmentRule;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.event.ExperimentActivatedEvent;
import fr.enimaloc.catapult.experiment.ExperimentSynchronizer;
import fr.enimaloc.catapult.getter.MockSteamApiClient;
import fr.enimaloc.catapult.repository.ExperimentAssignmentRuleRepository;
import fr.enimaloc.catapult.repository.ExperimentRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.service.ExperimentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
@Profile("mock-web")
@RequiredArgsConstructor
public class MockWebDataInitializer implements ApplicationRunner {

    @Value("${app.mock.users-count:3}")
    private int mockUsersCount;

    private final UserAccountRepository userAccountRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final ExperimentService experimentService;
    private final ExperimentRepository experimentRepository;
    private final ExperimentAssignmentRuleRepository ruleRepository;
    private final ExperimentSynchronizer experimentSynchronizer;
    private final ApplicationEventPublisher eventPublisher;
    private final Optional<MockSteamApiClient> mockSteamApiClient;

    @Override
    @SuppressWarnings("NullableProblems")
    public void run(ApplicationArguments args) {
        UserAccount admin = createUser("mock-admin", "admin_mock");
        createSettings(admin);
        experimentSynchronizer.updateVariant(admin);
        for (int i = 0; i < mockUsersCount; i++) {
            UserAccount user = createUser("mock-user-"+i, "user_mock_"+i);
            createSettings(user);
            experimentSynchronizer.updateVariant(user);
        }

        UserAccount privateSteamUser = createUserWithSteam("mock-steam-private", "user_steam_private", "steam-mock-private-76561");
        createSettings(privateSteamUser);
        experimentSynchronizer.updateVariant(privateSteamUser);
        mockSteamApiClient.ifPresent(c -> c.setProfilePrivate(privateSteamUser.getSteamId()));

        log.info("[Mock Web] Initialized users: {} (admin), {} * user, 1 private-steam",
                admin.getTwitchUsername(), mockUsersCount);

        // Auto-activate DRAFT experiments so each user immediately sees their correct variant.
        // Overrides have been resolved above by updateVariant, so assignments will use them.
        experimentRepository.findAll().stream()
                .filter(exp -> exp.getStatus() == Experiment.Status.DRAFT)
                .forEach(exp -> {
                    exp.setStatus(Experiment.Status.ACTIVE);
                    exp.setStartedAt(Instant.now());
                    experimentRepository.save(exp);
                    eventPublisher.publishEvent(new ExperimentActivatedEvent(this, exp.getId(), exp.getKey()));
                    log.info("[Mock Web] Auto-activated experiment '{}'", exp.getKey());
                });
    }

    @EventListener
    @Transactional
    public void onExperimentActivated(ExperimentActivatedEvent event) {
        Experiment experiment = experimentRepository.findById(event.getExperimentId()).orElseThrow();
        if (experiment.getRules().isEmpty()) {
            ExperimentAssignmentRule rule = new ExperimentAssignmentRule();
            rule.setExperiment(experiment);
            rule.setRuleType(ExperimentAssignmentRule.RuleType.RANDOM);
            rule.setPercentage(100);
            rule.setPriority(0);
            ruleRepository.save(rule);
            experiment.getRules().add(rule);
        }
        String key = event.getExperimentKey();
        userAccountRepository.findAll()
                .forEach(user -> experimentService.getVariant(user, key));
        log.info("[Mock Web] Eagerly assigned all users to experiment '{}'", key);
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

    private UserAccount createUserWithSteam(String twitchId, String username, String steamId) {
        return userAccountRepository.findByTwitchId(twitchId).orElseGet(() -> {
            UserAccount u = new UserAccount();
            u.setTwitchId(twitchId);
            u.setTwitchUsername(username);
            u.setStatus(UserAccount.Status.ACTIVE);
            u.setBotEnabled(true);
            u.setSteamId(steamId);
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
