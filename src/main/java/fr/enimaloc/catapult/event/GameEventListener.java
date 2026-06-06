package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.service.BindingService;
import fr.enimaloc.catapult.service.StreamStateService;
import fr.enimaloc.catapult.service.TwitchService;
import java.util.Optional;
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

        if (binding.isIgnored()) {
            log.debug("Binding is ignored — applying no-game fallback for user {}", user.getId());
            applyNoGameFallback(user);
            return;
        }

        if (binding.getStatus() == GameBinding.Status.INCOMPLETE) {
            log.debug("Binding is INCOMPLETE — checking fallback for user {}", user.getId());
            applyIncompleteFallback(user);
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
        applyNoGameFallback(user);
    }

    @EventListener
    public void onStreamOnline(StreamOnlineEvent event) {
        UserAccount user = event.getUser();
        log.debug("StreamOnlineEvent for user {}", user.getId());
        Optional<GameBinding> pending = streamStateService.getPending(user);
        if (pending.isPresent()) {
            twitchService.updateChannel(user, pending.get());
            streamStateService.clearPending(user);
        } else {
            userSettingsRepository.findById(user.getId()).ifPresent(settings -> {
                if (settings.isApplyDefaultOnStreamStart()) {
                    twitchService.resetToDefault(user);
                }
            });
        }
    }

    @EventListener
    public void onStreamOffline(StreamOfflineEvent event) {
        UserAccount user = event.getUser();
        log.debug("StreamOfflineEvent for user {}", user.getId());
        streamStateService.clearPending(user);
        userSettingsRepository.findById(user.getId()).ifPresent(settings -> {
            if (settings.isApplyDefaultOnStreamEnd()) {
                twitchService.resetToDefault(user);
            }
        });
    }

    private void applyNoGameFallback(UserAccount user) {
        if (!streamStateService.isLive(user)) {
            log.debug("User {} not live — skipping no-game fallback", user.getId());
            return;
        }
        userSettingsRepository.findById(user.getId()).ifPresent(settings -> {
            if (!settings.isApplyDefaultOnNoGame()) {
                log.debug("applyDefaultOnNoGame disabled for user {} — skipping", user.getId());
                return;
            }
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

    private void applyIncompleteFallback(UserAccount user) {
        userSettingsRepository.findById(user.getId()).ifPresent(settings -> {
            if (settings.getIncompleteFallbackTwitchGameId() == null
                    || settings.getIncompleteFallbackTwitchGameId().isBlank()) {
                log.debug("No incomplete fallback configured for user {} — skipping", user.getId());
                return;
            }
            GameBinding fallback = new GameBinding();
            fallback.setUser(user);
            fallback.setSourceType(GameBinding.SourceType.MANUAL);
            fallback.setSourceName("incomplete-fallback");
            fallback.setTwitchGameId(settings.getIncompleteFallbackTwitchGameId());
            fallback.setTwitchGameName(settings.getIncompleteFallbackTwitchGameName());
            fallback.getCcls().addAll(settings.getIncompleteFallbackCcls());
            fallback.setStatus(GameBinding.Status.MANUAL);

            if (streamStateService.isLive(user)) {
                twitchService.updateChannel(user, fallback);
            } else {
                streamStateService.storePending(user, fallback);
                log.debug("User {} not live — stored incomplete fallback as pending", user.getId());
            }
        });
    }
}
