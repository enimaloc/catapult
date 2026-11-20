package fr.enimaloc.catapult.service.stream;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.event.ChannelCategoryChangedEvent;
import fr.enimaloc.catapult.event.GameDetectedEvent;
import fr.enimaloc.catapult.event.NoGameDetectedEvent;
import fr.enimaloc.catapult.event.StreamOfflineEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService.CurrentCategory;
import fr.enimaloc.catapult.site.SiteRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Feeds the session timeline from the stream and detection events: the start of a live opens a period on the
 * channel's category and the last detected game, each category or game change moves to a new pair, and the end of
 * the live closes the running period. Only the active site writes; a failure is logged, never thrown to the
 * publisher.
 */
@Slf4j
@Component
public class SessionTimelineListener {

    private final SessionTimelineService timeline;
    private final TwitchEventSubService eventSub;
    private final GameStateService gameState;
    private final SiteRole siteRole;
    private final Clock clock;

    @Autowired
    public SessionTimelineListener(SessionTimelineService timeline, TwitchEventSubService eventSub,
                                   GameStateService gameState, SiteRole siteRole) {
        this(timeline, eventSub, gameState, siteRole, Clock.systemUTC());
    }

    SessionTimelineListener(SessionTimelineService timeline, TwitchEventSubService eventSub,
                            GameStateService gameState, SiteRole siteRole, Clock clock) {
        this.timeline = timeline;
        this.eventSub = eventSub;
        this.gameState = gameState;
        this.siteRole = siteRole;
        this.clock = clock;
    }

    @EventListener
    public void onStreamOnline(StreamOnlineEvent event) {
        record(event.getUser(), "stream start", user -> {
            Optional<CurrentCategory> category = initialCategory(user);
            String gameName = gameState.getLastKnownGame(user).map(DetectedGame::getSourceName).orElse(null);
            timeline.observe(user, category.map(CurrentCategory::id).orElse(null),
                    category.map(CurrentCategory::name).orElse(null), gameName, clock.instant());
        });
    }

    @EventListener
    public void onCategoryChanged(ChannelCategoryChangedEvent event) {
        record(event.getUser(), "category change", user ->
                timeline.observeCategory(user, event.getCategoryId(), event.getCategoryName(), clock.instant()));
    }

    @EventListener
    public void onGameDetected(GameDetectedEvent event) {
        String gameName = event.getDetectedGame() == null ? null : event.getDetectedGame().getSourceName();
        record(event.getUser(), "game detection", user -> timeline.observeGame(user, gameName, clock.instant()));
    }

    @EventListener
    public void onNoGameDetected(NoGameDetectedEvent event) {
        record(event.getUser(), "end of the game", user -> timeline.observeGame(user, null, clock.instant()));
    }

    @EventListener
    public void onStreamOffline(StreamOfflineEvent event) {
        record(event.getUser(), "stream end", user -> timeline.closeOpen(user, clock.instant()));
    }

    /**
     * The category EventSub knows, else a Helix read; unknown (empty) when both fail, so the period still opens and
     * takes the category at the first change.
     */
    private Optional<CurrentCategory> initialCategory(UserAccount user) {
        Optional<CurrentCategory> known = eventSub.currentCategory(user.getId());
        if (known.isPresent()) {
            return known;
        }
        try {
            return eventSub.fetchCurrentCategory(user);
        } catch (RuntimeException e) {
            log.warn("Could not read the category of user {} at the stream start: {}", user.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private void record(UserAccount user, String cause, Consumer<UserAccount> action) {
        if (!siteRole.isActive()) {
            return;
        }
        try {
            action.accept(user);
        } catch (RuntimeException e) {
            log.warn("Could not record the {} in the timeline of user {}: {}", cause, user.getId(), e.getMessage());
        }
    }
}
