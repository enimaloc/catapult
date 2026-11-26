package fr.enimaloc.catapult.service.gueststar;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.event.GuestStarsChangedEvent;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.title.StreamTitleService;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.site.SiteRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * The title followed live as the Guest Star guests come and go: {@value #DELAY_SECONDS} s after the last change (a burst
 * is one title), the last title sent normally is rendered again with the guests on air and sent alone, only while the
 * stream is live and Twitch still has the title Catapult sent last (one changed by hand is left alone).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuestStarTitleUpdater {

    static final long DELAY_SECONDS = 15;

    private final StreamTitleService streamTitleService;
    private final TwitchService twitchService;
    private final StreamStateService streamStateService;
    private final UserSettingsRepository userSettingsRepository;
    private final UserAccountRepository userAccountRepository;
    private final TaskScheduler taskScheduler;
    private final SiteRole siteRole;

    private final Map<UUID, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    @EventListener
    public void onGuestStarsChanged(GuestStarsChangedEvent event) {
        UUID userId = event.getUser().getId();
        pending.compute(userId, (id, previous) -> {
            if (previous != null) previous.cancel(false);
            return taskScheduler.schedule(() -> refresh(id), Instant.now().plus(Duration.ofSeconds(DELAY_SECONDS)));
        });
    }

    void refresh(UUID userId) {
        pending.remove(userId);
        try {
            if (!siteRole.isActive()) return;
            Optional<UserAccount> account = userAccountRepository.findById(userId);
            if (account.isEmpty() || !streamStateService.isLive(account.get())) return;
            UserAccount user = account.get();
            boolean titles = userSettingsRepository.findById(userId).map(UserSettings::isTitleFeatureEnabled)
                    .orElse(false);
            if (!titles) return;
            Optional<String> title = streamTitleService.guestStarsRefresh(user);
            Optional<String> lastSent = streamTitleService.lastSentTitle(user);
            if (title.isEmpty() || lastSent.isEmpty() || title.get().equals(lastSent.get())) return;
            if (twitchService.updateTitle(user, lastSent.get(), title.get()) == ChannelUpdateOutcome.APPLIED) {
                streamTitleService.sentRefresh(user, title.get());
            }
        } catch (RuntimeException e) {
            log.warn("Could not update the title of user {} for its Guest Star guests: {}", userId, e.getMessage());
        }
    }
}
