package fr.enimaloc.catapult.service.degraded;

import fr.enimaloc.catapult.common.dto.DegradedStatusDto;
import fr.enimaloc.catapult.event.DegradedStateChangedEvent;
import fr.enimaloc.catapult.service.ApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Follows catapult-api's degraded mode in the background, and the Instatus incident that explains it.
 * The incident is only looked up while degraded, and no more often than every
 * {@code catapult.degraded.instatus-refresh-ms}; the link found is kept between two lookups. When
 * catapult-api doesn't answer, the last view stays. A change is published as a
 * {@link DegradedStateChangedEvent}.
 */
@Slf4j
@Component
public class DegradedState {
    private final ApiService api;
    private final InstatusClient instatus;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String pageUrl;
    private final long instatusRefreshMs;

    private volatile DegradedView current = DegradedView.NONE;
    /** When Instatus was last asked, null when not since the degraded mode began. */
    private Instant instatusAskedAt;
    private String incidentUrl;

    @Autowired
    public DegradedState(ApiService api, InstatusClient instatus, ApplicationEventPublisher events,
                         @Value("${catapult.instatus.page-url:}") String pageUrl,
                         @Value("${catapult.degraded.instatus-refresh-ms:120000}") long instatusRefreshMs) {
        this(api, instatus, events, Clock.systemUTC(), pageUrl, instatusRefreshMs);
    }

    DegradedState(ApiService api, InstatusClient instatus, ApplicationEventPublisher events, Clock clock,
                  String pageUrl, long instatusRefreshMs) {
        this.api = api;
        this.instatus = instatus;
        this.events = events;
        this.clock = clock;
        this.pageUrl = pageUrl;
        this.instatusRefreshMs = instatusRefreshMs;
    }

    /** The degraded state as last known; never null. */
    public DegradedView current() {
        return current;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${catapult.degraded.refresh-ms:60000}")
    public void refresh() {
        try {
            DegradedStatusDto status = api.degradedStatus();
            if (status == null) return;
            DegradedView next = status.degraded() ? degradedView(status) : leaveDegraded();
            if (!next.equals(current)) {
                current = next;
                events.publishEvent(new DegradedStateChangedEvent(next));
            }
        } catch (RuntimeException e) {
            log.warn("Degraded state not refreshed", e);
        }
    }

    private DegradedView degradedView(DegradedStatusDto status) {
        boolean hasPage = pageUrl != null && !pageUrl.isBlank();
        if (hasPage) {
            Instant now = clock.instant();
            if (instatusAskedAt == null || !now.isBefore(instatusAskedAt.plusMillis(instatusRefreshMs))) {
                incidentUrl = instatus.latestIncidentUrl(pageUrl).orElse(null);
                instatusAskedAt = now;
            }
        }
        String link = incidentUrl != null ? incidentUrl : hasPage ? pageUrl : null;
        return new DegradedView(true, status.gameDetectionIntervalSeconds(), status.minecraftFriendsSyncMinutes(),
                link, incidentUrl != null);
    }

    private DegradedView leaveDegraded() {
        instatusAskedAt = null;
        incidentUrl = null;
        return DegradedView.NONE;
    }
}
