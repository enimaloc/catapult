package fr.enimaloc.catapult.service.itad;

import fr.enimaloc.catapult.common.dto.admin.ItadKeysPageData;
import fr.enimaloc.catapult.common.dto.admin.KeyStatus;
import fr.enimaloc.catapult.domain.itad.ItadApiKeyEntry;
import fr.enimaloc.catapult.getter.itad.ItadApiKeyRotator;
import fr.enimaloc.catapult.repository.itad.ItadApiKeyRepository;
import fr.enimaloc.catapult.service.ApiKeyHasher;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** The shared IsThereAnyDeal API keys pool the admin manages: listing (masked), adding, deleting, refreshing the rotator. */
@Service
public class ItadKeyPoolService {

    private final ItadApiKeyRepository repository;
    private final Optional<ItadApiKeyRotator> rotator;
    private final AdminEventPublisher events;

    /** The rotator only exists when IsThereAnyDeal is enabled. */
    public ItadKeyPoolService(ItadApiKeyRepository repository,
                              Optional<ItadApiKeyRotator> rotator,
                              AdminEventPublisher events) {
        this.repository = repository;
        this.rotator = rotator;
        this.events = events;
    }

    static String mask(String key) {
        return key.length() > 8
                ? key.substring(0, 4) + "…" + key.substring(key.length() - 4)
                : "…";
    }

    public ItadKeysPageData page() {
        List<ItadApiKeyEntry> entries = repository.findByExclusiveFalseWithOwner();
        Map<String, Long> blockedUntil = rotator.map(ItadApiKeyRotator::getKeyBlockedUntil).orElseGet(Map::of);
        long now = System.currentTimeMillis();

        List<KeyStatus> keys = new ArrayList<>();
        for (ItadApiKeyEntry entry : entries) {
            String key = entry.getApiKey();
            String masked = mask(key);
            String owner = entry.getOwner() != null ? entry.getOwner().getTwitchUsername() : null;
            long until = blockedUntil.getOrDefault(key, 0L);
            boolean blocked = until > now;
            long remainingSec = blocked ? TimeUnit.MILLISECONDS.toSeconds(until - now) : 0L;
            keys.add(new KeyStatus(ApiKeyHasher.id(key), masked, owner, blocked, remainingSec,
                    rotator.map(r -> r.usage(key)).orElse(null)));
        }
        return new ItadKeysPageData(keys, rotator.isPresent());
    }

    /** Adds the (trimmed, well-formed) key unless the pool has it already. */
    public void add(String apiKey) {
        if (!repository.existsById(apiKey)) {
            repository.save(new ItadApiKeyEntry(apiKey));
            rotator.ifPresent(ItadApiKeyRotator::refreshKeys);
            events.keyAdded(AdminEventPublisher.PROVIDER_ITAD,
                    new KeyStatus(ApiKeyHasher.id(apiKey), mask(apiKey), null, false, 0L));
        }
    }

    public void delete(String keyId) {
        Optional<String> raw = repository.findByExclusiveFalse().stream()
            .map(ItadApiKeyEntry::getApiKey)
            .filter(k -> ApiKeyHasher.id(k).equals(keyId))
            .findFirst();
        if (raw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown keyId");
        }
        repository.deleteById(raw.get());
        rotator.ifPresent(ItadApiKeyRotator::refreshKeys);
        events.keyDeleted(AdminEventPublisher.PROVIDER_ITAD, keyId);
    }

    public void refresh() {
        rotator.ifPresent(ItadApiKeyRotator::refreshKeys);
        events.keysRefreshed(AdminEventPublisher.PROVIDER_ITAD, page().keys());
    }
}
