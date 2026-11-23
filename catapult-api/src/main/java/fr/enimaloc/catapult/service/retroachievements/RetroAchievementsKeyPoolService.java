package fr.enimaloc.catapult.service.retroachievements;

import fr.enimaloc.catapult.common.dto.admin.RetroAchievementsKeysPageData;
import fr.enimaloc.catapult.common.dto.admin.KeyStatus;
import fr.enimaloc.catapult.domain.retroachievements.RetroAchievementsApiKeyEntry;
import fr.enimaloc.catapult.getter.retroachievements.RetroAchievementsApiKeyRotator;
import fr.enimaloc.catapult.repository.retroachievements.RetroAchievementsApiKeyRepository;
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

/** The shared RetroAchievements API keys pool the admin manages: listing (masked), adding, deleting, refreshing the rotator. */
@Service
public class RetroAchievementsKeyPoolService {

    private final RetroAchievementsApiKeyRepository repository;
    private final Optional<RetroAchievementsApiKeyRotator> rotator;
    private final AdminEventPublisher events;

    /** The rotator only exists when RetroAchievements is enabled. */
    public RetroAchievementsKeyPoolService(RetroAchievementsApiKeyRepository repository,
                              Optional<RetroAchievementsApiKeyRotator> rotator,
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

    public RetroAchievementsKeysPageData page() {
        List<RetroAchievementsApiKeyEntry> entries = repository.findByExclusiveFalseWithOwner();
        Map<String, Long> blockedUntil = rotator.map(RetroAchievementsApiKeyRotator::getKeyBlockedUntil).orElseGet(Map::of);
        long now = System.currentTimeMillis();

        List<KeyStatus> keys = new ArrayList<>();
        for (RetroAchievementsApiKeyEntry entry : entries) {
            String key = entry.getApiKey();
            String masked = mask(key);
            String owner = entry.getOwner() != null ? entry.getOwner().getTwitchUsername() : null;
            long until = blockedUntil.getOrDefault(key, 0L);
            boolean blocked = until > now;
            long remainingSec = blocked ? TimeUnit.MILLISECONDS.toSeconds(until - now) : 0L;
            keys.add(new KeyStatus(ApiKeyHasher.id(key), masked, owner, blocked, remainingSec,
                    rotator.map(r -> r.usage(key)).orElse(null)));
        }
        return new RetroAchievementsKeysPageData(keys, rotator.isPresent());
    }

    /** Adds the (trimmed, well-formed) key unless the pool has it already. */
    public void add(String apiKey) {
        if (!repository.existsById(apiKey)) {
            repository.save(new RetroAchievementsApiKeyEntry(apiKey));
            rotator.ifPresent(RetroAchievementsApiKeyRotator::refreshKeys);
            events.keyAdded(AdminEventPublisher.PROVIDER_RETROACHIEVEMENTS,
                    new KeyStatus(ApiKeyHasher.id(apiKey), mask(apiKey), null, false, 0L));
        }
    }

    public void delete(String keyId) {
        Optional<String> raw = repository.findByExclusiveFalse().stream()
            .map(RetroAchievementsApiKeyEntry::getApiKey)
            .filter(k -> ApiKeyHasher.id(k).equals(keyId))
            .findFirst();
        if (raw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown keyId");
        }
        repository.deleteById(raw.get());
        rotator.ifPresent(RetroAchievementsApiKeyRotator::refreshKeys);
        events.keyDeleted(AdminEventPublisher.PROVIDER_RETROACHIEVEMENTS, keyId);
    }

    public void refresh() {
        rotator.ifPresent(RetroAchievementsApiKeyRotator::refreshKeys);
        events.keysRefreshed(AdminEventPublisher.PROVIDER_RETROACHIEVEMENTS, page().keys());
    }
}
