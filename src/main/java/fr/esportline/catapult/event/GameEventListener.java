package fr.esportline.catapult.event;

import fr.esportline.catapult.domain.GameBinding;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.repository.UserSettingsRepository;
import fr.esportline.catapult.service.BindingService;
import fr.esportline.catapult.service.TwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listener principal des événements de détection de jeu.
 * Résout le binding et délègue à TwitchService pour la mise à jour de la chaîne.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameEventListener {

    private final BindingService bindingService;
    private final TwitchService twitchService;
    private final UserSettingsRepository userSettingsRepository;

    @EventListener
    public void onGameDetected(GameDetectedEvent event) {
        UserAccount user = event.getUser();
        log.debug("GameDetectedEvent for user {}: {}", user.getId(), event.getDetectedGame().getSourceName());

        GameBinding binding = bindingService.resolveOrCreate(user, event.getDetectedGame());

        if (binding.getStatus() == GameBinding.Status.INCOMPLETE || binding.isIgnored()) {
            log.debug("Binding is {} — skipping Twitch update for user {}", binding.getStatus(), user.getId());
            return;
        }

        twitchService.updateChannel(user, binding);
    }

    @EventListener
    public void onNoGameDetected(NoGameDetectedEvent event) {
        UserAccount user = event.getUser();
        log.debug("NoGameDetectedEvent for user {}", user.getId());

        userSettingsRepository.findById(user.getId()).ifPresent(settings -> {
            if (settings.getNoGameTwitchGameId() != null && !settings.getNoGameTwitchGameId().isBlank()) {
                // Appliquer la catégorie de fallback
                GameBinding fallbackBinding = new GameBinding();
                fallbackBinding.setUser(user);
                fallbackBinding.setSourceType(GameBinding.SourceType.MANUAL);
                fallbackBinding.setSourceName("no-game-fallback");
                fallbackBinding.setTwitchGameId(settings.getNoGameTwitchGameId());
                fallbackBinding.setTwitchGameName(settings.getNoGameTwitchGameName());
                fallbackBinding.setStatus(GameBinding.Status.MANUAL);

                twitchService.updateChannel(user, fallbackBinding);
            }
        });
    }
}
