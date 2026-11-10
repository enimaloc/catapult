package fr.enimaloc.catapult.service.drops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The running Twitch drops campaigns, kept in memory: refreshed from ttvdrops every {@code drops.refresh-ms}, read
 * without any call. A failed refresh keeps the last list, trusted for {@code drops.max-age-ms} (2 hours); older, or
 * never read, nothing is running.
 */
@Slf4j
@Service
public class DropsCatalog {

    private record Snapshot(List<DropCampaign> campaigns, Instant fetchedAt) {}

    private final TtvDropsClient client;
    private final Clock clock;
    private final boolean refreshEnabled;
    private final Duration maxAge;
    private volatile Snapshot snapshot;

    @Autowired
    public DropsCatalog(TtvDropsClient client,
                        @Value("${drops.refresh-enabled:true}") boolean refreshEnabled,
                        @Value("${drops.max-age-ms:7200000}") long maxAgeMs) {
        this(client, Clock.systemUTC(), refreshEnabled, Duration.ofMillis(maxAgeMs));
    }

    DropsCatalog(TtvDropsClient client, Clock clock, boolean refreshEnabled, Duration maxAge) {
        this.client = client;
        this.clock = clock;
        this.refreshEnabled = refreshEnabled;
        this.maxAge = maxAge;
    }

    @Scheduled(fixedDelayString = "${drops.refresh-ms:900000}", initialDelayString = "${drops.initial-delay-ms:0}")
    public void refresh() {
        if (!refreshEnabled) return;
        try {
            List<DropCampaign> campaigns = List.copyOf(client.activeCampaigns());
            snapshot = new Snapshot(campaigns, clock.instant());
            log.debug("[Drops] {} running campaigns read from ttvdrops", campaigns.size());
        } catch (RuntimeException e) {
            log.warn("[Drops] Could not refresh the campaigns from ttvdrops, keeping the last list: {}", e.getMessage());
        }
    }

    /** The campaigns running for the channel ({@code channelTwitchId}, null: unknown) on a category, soonest end first. */
    public List<DropCampaign> runningFor(String channelTwitchId, String categoryId) {
        if (categoryId == null) return List.of();
        Instant now = clock.instant();
        return campaigns().stream()
                .filter(c -> categoryId.equals(c.gameId()) && c.runningFor(channelTwitchId, now))
                .sorted(Comparator.comparing(DropCampaign::endAt))
                .toList();
    }

    /** For each of {@code categoryIds} with one, the campaign running for the channel that ends the soonest. */
    public Map<String, DropCampaign> firstEndingFor(String channelTwitchId, Collection<String> categoryIds) {
        Map<String, DropCampaign> first = new LinkedHashMap<>();
        for (String categoryId : categoryIds) {
            List<DropCampaign> running = runningFor(channelTwitchId, categoryId);
            if (!running.isEmpty()) first.put(categoryId, running.getFirst());
        }
        return first;
    }

    private List<DropCampaign> campaigns() {
        Snapshot current = snapshot;
        if (current == null || current.fetchedAt().plus(maxAge).isBefore(clock.instant())) return List.of();
        return current.campaigns();
    }
}
