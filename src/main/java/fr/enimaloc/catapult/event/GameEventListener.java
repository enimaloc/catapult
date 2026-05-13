package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.service.BindingService;
import fr.enimaloc.catapult.service.StreamStateService;
import fr.enimaloc.catapult.service.TwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameEventListener {

    private final BindingService bindingService;
    private final TwitchService twitchService;
    private final UserSettingsRepository userSettingsRepository;
    private final StreamStateService streamStateService;

    @EventListener
    public void onGameDetected(GameDetectedEvent event) {
        UserAccount user = event.getUser();
        log.debug("GameDetectedEvent for user {}: {}", user.getId(), event.getDetectedGame().getSourceName());

        GameBinding binding = bindingService.resolveOrCreate(user, event.getDetectedGame());

        if (binding.getStatus() == GameBinding.Status.INCOMPLETE || binding.isIgnored()) {
            log.debug("Binding is {} — skipping update for user {}", binding.getStatus(), user.getId());
            return;
        }

        if (streamStateService.isLive(user)) {
            twitchService.updateChannel(user, binding);
        } else {
            streamStateService.storePending(user, binding);
            log.debug("User {} not live — stored pending binding for game {}",
                user.getId(), binding.getSourceName());
        }
    }

    @EventListener
    public void onNoGameDetected(NoGameDetectedEvent event) {
        UserAccount user = event.getUser();
        log.debug("NoGameDetectedEvent for user {}", user.getId());

        if (!streamStateService.isLive(user)) {
            log.debug("User {} not live — skipping no-game fallback", user.getId());
            return;
        }

        userSettingsRepository.findById(user.getId()).ifPresent(settings -> {
            if (settings.getNoGameTwitchGameId() != null && !settings.getNoGameTwitchGameId().isBlank()) {
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

    @EventListener
    public void onStreamOnline(StreamOnlineEvent event) {
        UserAccount user = event.getUser();
        log.debug("StreamOnlineEvent for user {}", user.getId());
        streamStateService.getPending(user).ifPresent(binding -> {
            twitchService.updateChannel(user, binding);
            streamStateService.clearPending(user);
        });
    }

    @EventListener
    public void onStreamOffline(StreamOfflineEvent event) {
        UserAccount user = event.getUser();
        log.debug("StreamOfflineEvent for user {}", user.getId());
        twitchService.resetToDefault(user);
        streamStateService.clearPending(user);
    }
}
