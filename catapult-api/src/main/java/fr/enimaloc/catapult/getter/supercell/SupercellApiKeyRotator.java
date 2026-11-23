package fr.enimaloc.catapult.getter.supercell;

import fr.enimaloc.catapult.common.dto.admin.KeyUsageDto;
import fr.enimaloc.catapult.domain.supercell.SupercellApiKeyEntry;
import fr.enimaloc.catapult.getter.KeyCallCounter;
import fr.enimaloc.catapult.repository.supercell.SupercellApiKeyRepository;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellGame;
import fr.enimaloc.catapult.service.supercell.SupercellKeys;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The shared Supercell API keys, each of one game. Round robin over the keys of a game, skipping a key Supercell
 * refused from this server's IP address ({@code accessDenied.invalidIp}: the key is bound to other addresses), set
 * aside for that game only. No other refusal sets a key aside: a 403 that does not name the IP fails that call
 * alone, and a 429 pauses only the key's bucket in the {@code SupercellRateLimiter}. A game without a key is that
 * game's source being off.
 */
@Slf4j
@Component
public class SupercellApiKeyRotator {

    /** How long a key refused from this IP is kept out of its game's rotation. */
    public static final Duration IP_REFUSED_FOR = Duration.ofHours(1);

    private record PoolKey(String key, SupercellGame game) {
    }

    private final SupercellApiKeyRepository repository;
    private final Clock clock;
    /** By {@code game|key}: until when the key is refused from this IP. */
    private final Map<String, Long> refusedUntil = new ConcurrentHashMap<>();
    private final Map<SupercellGame, AtomicInteger> counters = new ConcurrentHashMap<>();
    private volatile List<PoolKey> keys = List.of();
    private final KeyCallCounter calls =
            new KeyCallCounter(Duration.ofMinutes(1), at -> at.atZone(ZoneOffset.UTC).toLocalDate());

    @Autowired
    public SupercellApiKeyRotator(SupercellApiKeyRepository repository, MeterRegistry meterRegistry) {
        this(repository, meterRegistry, Clock.systemUTC());
    }

    SupercellApiKeyRotator(SupercellApiKeyRepository repository, MeterRegistry meterRegistry, Clock clock) {
        this.repository = repository;
        this.clock = clock;
        Gauge.builder("catapult.external.keys", this, SupercellApiKeyRotator::availableKeyCount)
            .tag("api", "supercell").tag("state", "available")
            .description("Supercell keys not refused from this IP (shared pool)")
            .register(meterRegistry);
        Gauge.builder("catapult.external.keys", this, SupercellApiKeyRotator::blockedKeyCount)
            .tag("api", "supercell").tag("state", "blocked")
            .description("Supercell keys refused from this IP")
            .register(meterRegistry);
        refreshKeys();
    }

    public int blockedKeyCount() {
        long now = clock.millis();
        return (int) keys.stream().filter(k -> refused(k.game(), k.key(), now)).count();
    }

    public int availableKeyCount() {
        return keys.size() - blockedKeyCount();
    }

    /** Whether {@code game} has any key, refused or not: without one its source is off. */
    public boolean hasKeys(SupercellGame game) {
        return keys.stream().anyMatch(k -> k.game() == game);
    }

    public void refreshKeys() {
        this.keys = repository.findByExclusiveFalse().stream()
            .flatMap(e -> SupercellGame.parse(e.getGame()).map(g -> new PoolKey(e.getApiKey(), g)).stream())
            .toList();
        log.info("Supercell API key pool refreshed: {} key(s)", keys.size());
    }

    /** The next key of {@code game} not refused from this IP, round robin; empty when there is none. */
    public Optional<String> nextKey(SupercellGame game) {
        long now = clock.millis();
        List<String> usable = keys.stream()
            .filter(k -> k.game() == game && !refused(game, k.key(), now))
            .map(PoolKey::key)
            .toList();
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        int turn = counters.computeIfAbsent(game, g -> new AtomicInteger()).getAndIncrement();
        return Optional.of(usable.get(Math.floorMod(turn, usable.size())));
    }

    /** Whether {@code key} of {@code game} is refused from this IP now. */
    public boolean blocked(SupercellGame game, String key) {
        return refused(game, key, clock.millis());
    }

    private boolean refused(SupercellGame game, String key, long now) {
        return refusedUntil.getOrDefault(game.key() + "|" + key, 0L) > now;
    }

    /** By raw key, until when it is refused from this IP (for the admin tab). */
    public Map<String, Long> getKeyBlockedUntil() {
        long now = clock.millis();
        Map<String, Long> blocked = new HashMap<>();
        for (PoolKey k : keys) {
            long until = refusedUntil.getOrDefault(k.game().key() + "|" + k.key(), 0L);
            if (until > now) {
                blocked.put(k.key(), until);
            }
        }
        return Map.copyOf(blocked);
    }

    /** Supercell refused {@code key} from this server's IP ({@code accessDenied.invalidIp}): set aside for a while. */
    public void onIpRefused(SupercellGame game, String key) {
        refusedUntil.put(game.key() + "|" + key, clock.millis() + IP_REFUSED_FOR.toMillis());
        log.warn("Supercell {} key {} refused from this IP address, set aside for {} min", game.key(),
                SupercellKeys.mask(key), IP_REFUSED_FOR.toMinutes());
    }

    /** A call about to be sent with {@code key}: counted for the admin Keys tab. */
    public void onCall(String key) {
        calls.record(key, Instant.now());
    }

    /** What {@code key} was used for on this instance: its calls of the last minute and of the day (UTC). */
    public KeyUsageDto usage(String key) {
        Instant now = Instant.now();
        return new KeyUsageDto(calls.inWindow(key, now), null, 60, null, "DAY", calls.inPeriod(key, now), null,
                null);
    }
}
