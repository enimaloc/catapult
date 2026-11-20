package fr.enimaloc.catapult.service.stream;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.stream.StreamCategorySegment;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Feeds the session timeline from the stream and detection events: the start of a live opens a period on the
 * channel's category and the last detected game, each category or game change moves to a new pair, and the end of
 * the live closes the running period. Only the active site writes; a failure is logged, never thrown to the
 * publisher. The events arrive on the EventSub receive thread, so nothing here waits on Twitch: a category EventSub
 * does not know yet is read from Helix in the background.
 */
@Slf4j
@Component
public class SessionTimelineListener {

    private final SessionTimelineService timeline;
    private final TwitchEventSubService eventSub;
    private final GameStateService gameState;
    private final SiteRole siteRole;
    private final Clock clock;
    /** Runs the Helix reads, off the EventSub receive thread. */
    private final Executor helixReads;

    @Autowired
    public SessionTimelineListener(SessionTimelineService timeline, TwitchEventSubService eventSub,
                                   GameStateService gameState, SiteRole siteRole) {
        this(timeline, eventSub, gameState, siteRole, Clock.systemUTC(),
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("timeline-category-", 0).factory()));
    }

    SessionTimelineListener(SessionTimelineService timeline, TwitchEventSubService eventSub,
                            GameStateService gameState, SiteRole siteRole, Clock clock, Executor helixReads) {
        this.timeline = timeline;
        this.eventSub = eventSub;
        this.gameState = gameState;
        this.siteRole = siteRole;
        this.clock = clock;
        this.helixReads = helixReads;
    }

    /**
     * Opens the first period at once, on the category EventSub knows (none when the channel has none). When EventSub
     * knows nothing yet, the period opens with an unknown category and Helix is read in the background.
     */
    @EventListener
    public void onStreamOnline(StreamOnlineEvent event) {
        record(event.getUser(), "stream start", user -> {
            Instant start = clock.instant();
            Optional<CurrentCategory> known = eventSub.currentCategory(user.getId());
            CurrentCategory category = known.filter(c -> c.id() != null && !c.id().isBlank()).orElse(null);
            String gameName = gameState.getLastKnownGame(user).map(DetectedGame::getSourceName).orElse(null);
            timeline.observe(user, category == null ? null : category.id(),
                    category == null ? null : category.name(), gameName, start);
            if (known.isEmpty()) {
                helixReads.execute(() -> fillCategoryFromHelix(user, start));
            }
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
     * Reads the channel's category from Helix and gives it to the period opened at {@code start}, in place, unless
     * the running period already has a category (a {@code channel.update} got there first).
     */
    private void fillCategoryFromHelix(UserAccount user, Instant start) {
        try {
            Optional<CurrentCategory> category = eventSub.fetchCurrentCategory(user)
                    .filter(c -> c.id() != null && !c.id().isBlank());
            if (category.isEmpty() || !runningCategoryUnknown(user)) {
                return;
            }
            timeline.observeCategory(user, category.get().id(), category.get().name(), start);
        } catch (RuntimeException e) {
            log.warn("Could not read the category of user {} at the stream start", user.getId(), e);
        }
    }

    private boolean runningCategoryUnknown(UserAccount user) {
        List<StreamCategorySegment> segments = timeline.currentSession(user);
        return !segments.isEmpty() && segments.getLast().getEndedAt() == null
                && segments.getLast().getCategoryId() == null;
    }

    private void record(UserAccount user, String cause, Consumer<UserAccount> action) {
        if (!siteRole.isActive()) {
            return;
        }
        try {
            action.accept(user);
        } catch (RuntimeException e) {
            log.warn("Could not record the {} in the timeline of user {}", cause, user.getId(), e);
        }
    }
}
