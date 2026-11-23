package fr.enimaloc.catapult.service.faceit;

import fr.enimaloc.catapult.common.dto.admin.FaceitKeysPageData;
import fr.enimaloc.catapult.common.dto.admin.KeyStatus;
import fr.enimaloc.catapult.domain.faceit.FaceitApiKeyEntry;
import fr.enimaloc.catapult.getter.faceit.FaceitApiKeyRotator;
import fr.enimaloc.catapult.repository.faceit.FaceitApiKeyRepository;
import fr.enimaloc.catapult.service.ApiKeyHasher;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
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
 * The shared FACEIT Data API keys pool the admin manages: listing (masked), adding, deleting, refreshing the rotator.
 * While it is empty, the FACEIT source is off.
 */
@Service
public class FaceitKeyPoolService {

    private final FaceitApiKeyRepository repository;
    private final FaceitApiKeyRotator rotator;
    private final EnrichmentProperties properties;
    private final AdminEventPublisher events;

    public FaceitKeyPoolService(FaceitApiKeyRepository repository, FaceitApiKeyRotator rotator,
                                EnrichmentProperties properties, AdminEventPublisher events) {
        this.repository = repository;
        this.rotator = rotator;
        this.properties = properties;
        this.events = events;
    }

    static String mask(String key) {
        return key.length() > 8 ? key.substring(0, 4) + "…" + key.substring(key.length() - 4) : "…";
    }

    public FaceitKeysPageData page() {
        Map<String, Long> blockedUntil = rotator.getKeyBlockedUntil();
        long now = System.currentTimeMillis();
        List<KeyStatus> keys = new ArrayList<>();
        for (FaceitApiKeyEntry entry : repository.findByExclusiveFalseWithOwner()) {
            String key = entry.getApiKey();
            String owner = entry.getOwner() != null ? entry.getOwner().getTwitchUsername() : null;
            long until = blockedUntil.getOrDefault(key, 0L);
            boolean blocked = until > now;
            long remainingSec = blocked ? TimeUnit.MILLISECONDS.toSeconds(until - now) : 0L;
            keys.add(new KeyStatus(ApiKeyHasher.id(key), mask(key), owner, blocked, remainingSec,
                    rotator.usage(key)));
        }
        return new FaceitKeysPageData(keys, properties.faceitEnabled());
    }

    /** Adds the (trimmed, well-formed) key unless the pool has it already. */
    public void add(String apiKey) {
        if (!repository.existsById(apiKey)) {
            repository.save(new FaceitApiKeyEntry(apiKey));
            rotator.refreshKeys();
            events.keyAdded(AdminEventPublisher.PROVIDER_FACEIT,
                    new KeyStatus(ApiKeyHasher.id(apiKey), mask(apiKey), null, false, 0L));
        }
    }

    public void delete(String keyId) {
        Optional<String> raw = repository.findByExclusiveFalse().stream()
            .map(FaceitApiKeyEntry::getApiKey)
            .filter(k -> ApiKeyHasher.id(k).equals(keyId))
            .findFirst();
        if (raw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown keyId");
        }
        repository.deleteById(raw.get());
        rotator.refreshKeys();
        events.keyDeleted(AdminEventPublisher.PROVIDER_FACEIT, keyId);
    }

    public void refresh() {
        rotator.refreshKeys();
        events.keysRefreshed(AdminEventPublisher.PROVIDER_FACEIT, page().keys());
    }
}
