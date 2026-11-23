package fr.enimaloc.catapult.service.riot;

import fr.enimaloc.catapult.common.dto.admin.KeyStatus;
import fr.enimaloc.catapult.common.dto.admin.RiotKeysPageData;
import fr.enimaloc.catapult.domain.riot.RiotApiKeyEntry;
import fr.enimaloc.catapult.getter.riot.RiotApiKeyRotator;
import fr.enimaloc.catapult.repository.riot.RiotApiKeyRepository;
import fr.enimaloc.catapult.service.ApiKeyHasher;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.riot.RiotPlatform.Cluster;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The shared Riot API keys pool the admin manages: listing (masked), adding (optionally kept to one cluster),
 * deleting, refreshing the rotator. While it is empty, the Riot source is off.
 */
@Service
public class RiotKeyPoolService {

    private final RiotApiKeyRepository repository;
    private final RiotApiKeyRotator rotator;
    private final EnrichmentProperties properties;
    private final AdminEventPublisher events;

    public RiotKeyPoolService(RiotApiKeyRepository repository, RiotApiKeyRotator rotator,
                              EnrichmentProperties properties, AdminEventPublisher events) {
        this.repository = repository;
        this.rotator = rotator;
        this.properties = properties;
        this.events = events;
    }

    static String mask(String key) {
        return key.length() > 8 ? key.substring(0, 4) + "…" + key.substring(key.length() - 4) : "…";
    }

    public RiotKeysPageData page() {
        Map<String, Long> blockedUntil = rotator.getKeyBlockedUntil();
        long now = System.currentTimeMillis();
        List<KeyStatus> keys = new ArrayList<>();
        for (RiotApiKeyEntry entry : repository.findByExclusiveFalseWithOwner()) {
            String key = entry.getApiKey();
            String owner = entry.getOwner() != null ? entry.getOwner().getTwitchUsername() : null;
            long until = blockedUntil.getOrDefault(key, 0L);
            boolean blocked = until > now;
            long remainingSec = blocked ? TimeUnit.MILLISECONDS.toSeconds(until - now) : 0L;
            keys.add(new KeyStatus(ApiKeyHasher.id(key), maskWithScope(entry), owner, blocked, remainingSec,
                    rotator.usage(key)));
        }
        return new RiotKeysPageData(keys, properties.riotEnabled());
    }

    private static String maskWithScope(RiotApiKeyEntry entry) {
        String masked = mask(entry.getApiKey());
        return entry.getRegionScope() == null ? masked : masked + " (" + entry.getRegionScope() + ")";
    }

    /** Adds the (trimmed, well-formed) key unless the pool has it already; {@code scope} null serves every cluster. */
    public void add(String apiKey, Optional<Cluster> scope) {
        if (!repository.existsById(apiKey)) {
            RiotApiKeyEntry entry = new RiotApiKeyEntry(apiKey, scope.map(Cluster::key).orElse(null));
            repository.save(entry);
            rotator.refreshKeys();
            events.keyAdded(AdminEventPublisher.PROVIDER_RIOT,
                    new KeyStatus(ApiKeyHasher.id(apiKey), maskWithScope(entry), null, false, 0L));
        }
    }

    public void delete(String keyId) {
        Optional<String> raw = repository.findByExclusiveFalse().stream()
            .map(RiotApiKeyEntry::getApiKey)
            .filter(k -> ApiKeyHasher.id(k).equals(keyId))
            .findFirst();
        if (raw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown keyId");
        }
        repository.deleteById(raw.get());
        rotator.refreshKeys();
        events.keyDeleted(AdminEventPublisher.PROVIDER_RIOT, keyId);
    }

    public void refresh() {
        rotator.refreshKeys();
        events.keysRefreshed(AdminEventPublisher.PROVIDER_RIOT, page().keys());
    }
}
