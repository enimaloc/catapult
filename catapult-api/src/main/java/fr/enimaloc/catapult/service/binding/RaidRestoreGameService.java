package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.event.ChannelRaidedOutEvent;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A streamer who closes their game gets the "no game" category; raiding out minutes later would then
 * leave from it. With the channel's {@link UserSettings#getRaidRestoreGameMinutes() delay} set (and the
 * {@value RaidRestoreGateService#EXPERIMENT_KEY} gate on), an outgoing raid puts the game detected within
 * that delay back (unless the channel is already on it), and the "no game" fallback then holds off until the
 * stream ends or a new game is detected, so a later detection doesn't undo it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RaidRestoreGameService {

    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 120;

    private final UserSettingsRepository userSettingsRepository;
    private final RaidRestoreGateService gate;
    private final GameStateService gameStateService;
    private final BindingService bindingService;
    private final TwitchService twitchService;

    /** Channels whose game a raid put back: no "no game" fallback for them until released. In memory, like the game state. */
    private final Set<UUID> holdingNoGameFallback = ConcurrentHashMap.newKeySet();

    @EventListener
    public void onRaidedOut(ChannelRaidedOutEvent event) {
        UserAccount user = event.getUser();
        Integer minutes = userSettingsRepository.findById(user.getId())
                .map(UserSettings::getRaidRestoreGameMinutes)
                .filter(m -> m >= MIN_MINUTES)
                .orElse(null);
        if (minutes == null || !gate.isAvailableReadOnly(user)) return;

        Optional<DetectedGame> recent = gameStateService.recentlyDetectedGame(user, Duration.ofMinutes(minutes));
        Optional<GameBinding> binding = recent
                .flatMap(game -> bindingService.findBinding(user, game))
                .filter(b -> !b.isIgnored() && b.getStatus() != GameBinding.Status.INCOMPLETE)
                .filter(b -> b.getTwitchGameId() != null && !b.getTwitchGameId().isBlank());
        if (binding.isEmpty()) {
            log.debug("Raid by user {}: no game detected within {} min to put back", user.getId(), minutes);
            return;
        }
        // Held first, so a detection tick running meanwhile can't put the "no game" category back after us;
        // also when already on the game, which may still be closed before the stream ends.
        holdingNoGameFallback.add(user.getId());
        if (binding.get().getTwitchGameId().equals(event.getCurrentCategoryId())) {
            log.debug("Raid by user {}: already on {}", user.getId(), binding.get().getTwitchGameName());
            return;
        }
        log.info("Raid by user {}: putting {} back as the category", user.getId(), binding.get().getTwitchGameName());
        try {
            // updateChannel records Catapult's own change, so the channel.update isn't taken for a manual one
            twitchService.updateChannel(user, binding.get());
        } catch (RuntimeException e) {
            holdingNoGameFallback.remove(user.getId());
            log.warn("Raid by user {}: could not put {} back: {}", user.getId(),
                    binding.get().getTwitchGameName(), e.getMessage());
        }
    }

    /** True while a raid's restored game must not be replaced by the "no game" category. */
    public boolean isHoldingNoGameFallback(UserAccount user) {
        return holdingNoGameFallback.contains(user.getId());
    }

    /** Ends the hold: the stream ended or a new game was detected. */
    public void releaseHold(UserAccount user) {
        holdingNoGameFallback.remove(user.getId());
    }
}
