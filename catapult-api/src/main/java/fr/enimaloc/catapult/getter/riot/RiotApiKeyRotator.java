package fr.enimaloc.catapult.getter.riot;

import fr.enimaloc.catapult.common.dto.admin.KeyUsageDto;
import fr.enimaloc.catapult.domain.riot.RiotApiKeyEntry;
import fr.enimaloc.catapult.getter.KeyCallCounter;
import fr.enimaloc.catapult.repository.riot.RiotApiKeyRepository;
import fr.enimaloc.catapult.service.ApiKeyHasher;
import fr.enimaloc.catapult.service.enrichment.riot.RiotPlatform.Cluster;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The shared Riot API keys. Round robin over the keys that serve a routing cluster (those scoped to it, and the
 * unscoped ones), skipping a key Riot refused (401) <em>for that cluster</em> and a key Riot does not let use the
 * routes of a game (403: a League of Legends product key on the Teamfight Tactics routes), remembered per key and per
 * game. A rate limit (429) is not a set-aside here: the {@code RiotRateLimiter} pauses only the key's bucket on the
 * host that answered. An empty pool is the Riot source being off. A key is also found by its id
 * ({@link ApiKeyHasher#id}), the one stored with the PUUIDs it resolved: Riot's ids are encrypted per key project.
 */
@Slf4j
@Component
public class RiotApiKeyRotator {

    /** The route families a key may or may not use: the account routes, and those of each game. */
    public enum Family {
        ACCOUNT, LOL, TFT, VALORANT
    }

    /** How long a key Riot does not let use a family's routes is kept off them. */
    static final Duration FORBIDDEN_FOR = Duration.ofHours(6);

    /** A key of the pool and the cluster it is kept to (null: every one). */
    private record PoolKey(String key, String id, Cluster scope) {
    }

    private final RiotApiKeyRepository repository;
    private final Clock clock;
    /** By {@code key|cluster}: until when the key is refused there. */
    private final Map<String, Long> rejectedUntil = new ConcurrentHashMap<>();
    /** By {@code key|family}: until when the key may not use the family's routes. */
    private final Map<String, Long> forbiddenUntil = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);
    private volatile List<PoolKey> keys = List.of();
    private final KeyCallCounter calls =
            new KeyCallCounter(Duration.ofMinutes(2), at -> at.atZone(ZoneOffset.UTC).toLocalDate());

    @Autowired
    public RiotApiKeyRotator(RiotApiKeyRepository repository, MeterRegistry meterRegistry) {
        this(repository, meterRegistry, Clock.systemUTC());
    }

    RiotApiKeyRotator(RiotApiKeyRepository repository, MeterRegistry meterRegistry, Clock clock) {
        this.repository = repository;
        this.clock = clock;
        Gauge.builder("catapult.external.keys", this, RiotApiKeyRotator::availableKeyCount)
            .tag("api", "riot").tag("state", "available")
            .description("Riot keys refused nowhere (shared pool)")
            .register(meterRegistry);
        Gauge.builder("catapult.external.keys", this, RiotApiKeyRotator::blockedKeyCount)
            .tag("api", "riot").tag("state", "blocked")
            .description("Riot keys refused on at least one cluster")
            .register(meterRegistry);
        refreshKeys();
    }

    public int blockedKeyCount() {
        return (int) keys.stream().filter(k -> blockedUntil(k.key()) > clock.millis()).count();
    }

    public int availableKeyCount() {
        return keys.size() - blockedKeyCount();
    }

    /** Whether the pool holds any key, blocked or not: without one the Riot source is off. */
    public boolean hasKeys() {
        return !keys.isEmpty();
    }

    public void refreshKeys() {
        this.keys = repository.findByExclusiveFalse().stream()
            .map(e -> new PoolKey(e.getApiKey(), ApiKeyHasher.id(e.getApiKey()),
                    Cluster.parse(e.getRegionScope()).orElse(null)))
            .toList();
        log.info("Riot API key pool refreshed: {} key(s)", keys.size());
    }

    /**
     * The next key for {@code family} on {@code cluster}, round robin among those neither refused there nor kept off
     * the family; empty when none serves the cluster or every one of them is set aside.
     */
    public Optional<String> nextKey(Cluster cluster, Family family) {
        long now = clock.millis();
        List<String> usable = serving(cluster)
            .filter(k -> !refused(k.key(), cluster, now) && !forbidden(k.key(), family, now))
            .map(PoolKey::key)
            .toList();
        if (usable.isEmpty()) return Optional.empty();
        return Optional.of(usable.get(Math.floorMod(counter.getAndIncrement(), usable.size())));
    }

    /** The key of id {@code keyId}, when it is still in the pool and serves {@code cluster}. */
    public Optional<String> keyFor(String keyId, Cluster cluster) {
        if (keyId == null) return Optional.empty();
        return serving(cluster).filter(k -> k.id().equals(keyId)).map(PoolKey::key).findFirst();
    }

    /** Whether some key serves {@code cluster}. */
    public boolean serves(Cluster cluster) {
        return serving(cluster).findAny().isPresent();
    }

    private java.util.stream.Stream<PoolKey> serving(Cluster cluster) {
        return keys.stream().filter(k -> k.scope() == null || k.scope() == cluster);
    }

    /** Whether {@code key} is refused on {@code cluster} now. */
    public boolean blocked(String key, Cluster cluster) {
        return refused(key, cluster, clock.millis());
    }

    /** Whether {@code key} is kept off the routes of {@code family} now. */
    public boolean forbidden(String key, Family family) {
        return forbidden(key, family, clock.millis());
    }

    private boolean refused(String key, Cluster cluster, long now) {
        return rejectedUntil.getOrDefault(key + "|" + cluster, 0L) > now;
    }

    private boolean forbidden(String key, Family family, long now) {
        return forbiddenUntil.getOrDefault(key + "|" + family, 0L) > now;
    }

    /** By raw key, the latest time it is refused on some cluster (for the admin tab). */
    public Map<String, Long> getKeyBlockedUntil() {
        Map<String, Long> latest = new java.util.HashMap<>();
        for (PoolKey k : keys) {
            long until = blockedUntil(k.key());
            if (until > 0) latest.put(k.key(), until);
        }
        return Map.copyOf(latest);
    }

    private long blockedUntil(String key) {
        return rejectedUntil.entrySet().stream().filter(e -> e.getKey().startsWith(key + "|"))
            .map(Map.Entry::getValue).max(Comparator.naturalOrder()).orElse(0L);
    }

    /** The key Riot refused (401) on {@code cluster}: set aside there for {@code duration}. */
    public void onKeyRejected(String key, Cluster cluster, Duration duration) {
        rejectedUntil.put(key + "|" + cluster, clock.millis() + duration.toMillis());
        log.warn("Riot key {} refused on {}, set aside there for {}s", mask(key), cluster.key(),
                duration.toSeconds());
    }

    /** Riot does not let {@code key} use the routes of {@code family} (403): kept off them for a while. */
    public void onForbidden(String key, Family family) {
        forbiddenUntil.put(key + "|" + family, clock.millis() + FORBIDDEN_FOR.toMillis());
        log.warn("Riot key {} may not use the {} routes, kept off them for {}h", mask(key), family,
                FORBIDDEN_FOR.toHours());
    }

    static String mask(String key) {
        return key.length() > 8 ? key.substring(0, 4) + "…" + key.substring(key.length() - 4) : "…";
    }

    /** A call about to be sent with {@code key}: counted for the admin Keys tab. */
    public void onCall(String key) {
        calls.record(key, Instant.now());
    }

    /** What {@code key} was used for on this instance: its calls of the last two minutes and of the day (UTC). */
    public KeyUsageDto usage(String key) {
        Instant now = Instant.now();
        return new KeyUsageDto(calls.inWindow(key, now), null, 120, null, "DAY", calls.inPeriod(key, now), null,
                null);
    }
}
