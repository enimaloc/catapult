package fr.enimaloc.catapult.service.dtdd;

import fr.enimaloc.catapult.common.dto.admin.DtddKeysPageData;
import fr.enimaloc.catapult.common.dto.admin.KeyStatus;
import fr.enimaloc.catapult.domain.dtdd.DtddApiKeyEntry;
import fr.enimaloc.catapult.getter.dtdd.DtddApiKeyRotator;
import fr.enimaloc.catapult.repository.dtdd.DtddApiKeyRepository;
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

/** The shared DTDD API keys pool the admin manages: listing (masked), adding, deleting, refreshing the rotator. */
@Service
public class DtddKeyPoolService {

    private final DtddApiKeyRepository repository;
    private final Optional<DtddApiKeyRotator> rotator;
    private final AdminEventPublisher events;

    /** The rotator only exists when DTDD is enabled. */
    public DtddKeyPoolService(DtddApiKeyRepository repository,
                              Optional<DtddApiKeyRotator> rotator,
                              AdminEventPublisher events) {
        this.repository = repository;
        this.rotator = rotator;
        this.events = events;
    }

    private static String mask(String key) {
        return key.length() > 8
                ? key.substring(0, 4) + "…" + key.substring(key.length() - 4)
                : "…";
    }

    public DtddKeysPageData page() {
        List<DtddApiKeyEntry> entries = repository.findByExclusiveFalseWithOwner();
        Map<String, Long> blockedUntil = rotator.map(DtddApiKeyRotator::getKeyBlockedUntil).orElseGet(Map::of);
        long now = System.currentTimeMillis();

        List<KeyStatus> keys = new ArrayList<>();
        for (DtddApiKeyEntry entry : entries) {
            String key = entry.getApiKey();
            String masked = mask(key);
            String owner = entry.getOwner() != null ? entry.getOwner().getTwitchUsername() : null;
            long until = blockedUntil.getOrDefault(key, 0L);
            boolean blocked = until > now;
            long remainingSec = blocked ? TimeUnit.MILLISECONDS.toSeconds(until - now) : 0L;
            keys.add(new KeyStatus(ApiKeyHasher.id(key), masked, owner, blocked, remainingSec));
        }
        return new DtddKeysPageData(keys, rotator.isPresent());
    }

    /** Adds the (trimmed, well-formed) key unless the pool has it already. */
    public void add(String apiKey) {
        if (!repository.existsById(apiKey)) {
            repository.save(new DtddApiKeyEntry(apiKey));
            rotator.ifPresent(DtddApiKeyRotator::refreshKeys);
            events.keyAdded(AdminEventPublisher.PROVIDER_DTDD,
                    new KeyStatus(ApiKeyHasher.id(apiKey), mask(apiKey), null, false, 0L));
        }
    }

    public void delete(String keyId) {
        Optional<String> raw = repository.findByExclusiveFalse().stream()
            .map(DtddApiKeyEntry::getApiKey)
            .filter(k -> ApiKeyHasher.id(k).equals(keyId))
            .findFirst();
        if (raw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown keyId");
        }
        repository.deleteById(raw.get());
        rotator.ifPresent(DtddApiKeyRotator::refreshKeys);
        events.keyDeleted(AdminEventPublisher.PROVIDER_DTDD, keyId);
    }

    public void refresh() {
        rotator.ifPresent(DtddApiKeyRotator::refreshKeys);
        events.keysRefreshed(AdminEventPublisher.PROVIDER_DTDD, page().keys());
    }
}
