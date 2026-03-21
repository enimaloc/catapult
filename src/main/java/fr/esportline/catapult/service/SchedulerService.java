package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.event.GameDetectedEvent;
import fr.esportline.catapult.event.NoGameDetectedEvent;
import fr.esportline.catapult.getter.DetectedGame;
import fr.esportline.catapult.getter.GameGetterChain;
import fr.esportline.catapult.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Scheduler global qui itère sur tous les utilisateurs actifs avec bot activé
 * et publie des événements Spring si l'état du jeu a changé.
 */
@Slf4j
@Service
@DependsOn("flyway")
@RequiredArgsConstructor
public class SchedulerService {

    private final UserAccountRepository userAccountRepository;
    private final GameGetterChain gameGetterChain;
    private final GameStateService gameStateService;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedRateString = "${app.polling.interval-seconds:60}000")
    public void poll() {
        List<UserAccount> activeUsers = userAccountRepository
            .findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE);

        for (UserAccount user : activeUsers) {
            try {
                processUser(user);
            } catch (Exception e) {
                log.error("Unexpected error during polling for user {}", user.getId(), e);
            }
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
            // Aucun jeu détecté — publier seulement si l'utilisateur était en train de jouer
            if (gameStateService.getLastKnownGame(user).isPresent()) {
                gameStateService.clearState(user);
                log.debug("No game detected for user {} (was playing)", user.getId());
                eventPublisher.publishEvent(new NoGameDetectedEvent(this, user));
            }
        }
    }
}
