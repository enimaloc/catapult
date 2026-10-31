package fr.enimaloc.catapult.service.steam;

import fr.enimaloc.catapult.common.dto.admin.KeyStatus;
import fr.enimaloc.catapult.common.dto.admin.SteamKeysPageData;
import fr.enimaloc.catapult.domain.steam.SteamApiKeyEntry;
import fr.enimaloc.catapult.getter.steam.SteamApiKeyRotator;
import fr.enimaloc.catapult.repository.steam.SteamApiKeyRepository;
import fr.enimaloc.catapult.service.ApiKeyHasher;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** The shared Steam API keys pool the admin manages: listing (masked), adding, deleting, refreshing the rotator. */
@Service
public class SteamKeyPoolService {

    private final SteamApiKeyRepository repository;
    private final Optional<SteamApiKeyRotator> rotator;
    private final AdminEventPublisher events;

    /** The rotator only exists when Steam is enabled. */
    public SteamKeyPoolService(SteamApiKeyRepository repository,
                               Optional<SteamApiKeyRotator> rotator,
                               AdminEventPublisher events) {
        this.repository = repository;
        this.rotator = rotator;
        this.events = events;
    }

    /** A shared key's id, masked value, owner and remaining rate-limit block. */
    private static KeyStatus status(SteamApiKeyEntry entry, Map<String, Long> blockedUntil, long now) {
        String key = entry.getApiKey();
        String owner = entry.getOwner() != null ? entry.getOwner().getTwitchUsername() : null;
        long until = blockedUntil.getOrDefault(key, 0L);
        boolean blocked = until > now;
        long remainingSec = blocked ? TimeUnit.MILLISECONDS.toSeconds(until - now) : 0L;
        return new KeyStatus(ApiKeyHasher.id(key), mask(key), owner, blocked, remainingSec);
    }

    private void refreshRotator() {
        rotator.ifPresent(SteamApiKeyRotator::refreshKeys);
    }

    private static String mask(String key) {
        return key.length() > 8
                ? key.substring(0, 4) + "…" + key.substring(key.length() - 4)
                : "…";
    }

    public SteamKeysPageData page() {
        List<SteamApiKeyEntry> entries = repository.findByExclusiveFalseWithOwner();
        Map<String, Long> blockedUntil = rotator.map(SteamApiKeyRotator::getKeyBlockedUntil).orElseGet(Map::of);
        long now = System.currentTimeMillis();

        List<KeyStatus> keys = entries.stream().map(entry -> status(entry, blockedUntil, now)).toList();
        return new SteamKeysPageData(keys, rotator.isPresent());
    }

    /** Adds the (trimmed, well-formed) key unless the pool has it already. */
    public void add(String apiKey) {
        if (!repository.existsById(apiKey)) {
            repository.save(new SteamApiKeyEntry(apiKey));
            refreshRotator();
            events.keyAdded(AdminEventPublisher.PROVIDER_STEAM,
                    new KeyStatus(ApiKeyHasher.id(apiKey), mask(apiKey), null, false, 0L));
        }
    }

    public void delete(String keyId) {
        Optional<String> raw = repository.findByExclusiveFalse().stream()
            .map(SteamApiKeyEntry::getApiKey)
            .filter(k -> ApiKeyHasher.id(k).equals(keyId))
            .findFirst();
        if (raw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown keyId");
        }
        repository.deleteById(raw.get());
        refreshRotator();
        events.keyDeleted(AdminEventPublisher.PROVIDER_STEAM, keyId);
    }

    public void refresh() {
        refreshRotator();
        events.keysRefreshed(AdminEventPublisher.PROVIDER_STEAM, page().keys());
    }
}
