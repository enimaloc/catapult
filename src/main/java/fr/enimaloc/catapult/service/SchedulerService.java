package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.GameDetectedEvent;
import fr.enimaloc.catapult.event.NoGameDetectedEvent;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.GameGetterChain;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@DependsOn("flyway")
@RequiredArgsConstructor
public class SchedulerService {

    private final UserAccountRepository userAccountRepository;
    private final GameGetterChain gameGetterChain;
    private final GameStateService gameStateService;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedRateString = "${app.polling.interval-seconds:60}000")
    public void poll() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<UserAccount> activeUsers = userAccountRepository
                .findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE);

            for (UserAccount user : activeUsers) {
                try {
                    processUser(user);
                } catch (Exception e) {
                    log.error("Unexpected error during polling for user {}", user.getId(), e);
                }
                meterRegistry.counter("catapult.scheduler.users.polled").increment();
            }
        } finally {
            sample.stop(Timer.builder("catapult.scheduler.poll.duration").register(meterRegistry));
        }
    }

    private void processUser(UserAccount user) {
        Optional<DetectedGame> detected = gameGetterChain.resolve(user);

        if (detected.isPresent()) {
            DetectedGame game = detected.get();
            if (gameStateService.hasChanged(user, game)) {
                gameStateService.updateState(user, game);
                log.debug("Game changed for user {}: {}", user.getId(), game.getSourceName());
                eventPublisher.publishEvent(new GameDetectedEvent(this, user, game));
            }
        } else {
            if (gameStateService.getLastKnownGame(user).isPresent()) {
                gameStateService.clearState(user);
                log.debug("No game detected for user {} (was playing)", user.getId());
                eventPublisher.publishEvent(new NoGameDetectedEvent(this, user));
            }
        }
    }
}
