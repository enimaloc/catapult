package fr.enimaloc.catapult.service.supercell;

import fr.enimaloc.catapult.common.dto.admin.KeyStatus;
import fr.enimaloc.catapult.common.dto.admin.SupercellKeysPageData;
import fr.enimaloc.catapult.domain.supercell.SupercellApiKeyEntry;
import fr.enimaloc.catapult.getter.supercell.SupercellApiKeyRotator;
import fr.enimaloc.catapult.repository.supercell.SupercellApiKeyRepository;
import fr.enimaloc.catapult.service.ApiKeyHasher;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellGame;
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
 * The shared Supercell API keys the admin manages, one pool per game: listing (masked, with the IP ranges the key is
 * bound to when its JWT says so), adding, deleting, refreshing the rotator. While a game's pool is empty, its source
 * is off.
 */
@Service
public class SupercellKeyPoolService {

    private final SupercellApiKeyRepository repository;
    private final SupercellApiKeyRotator rotator;
    private final EnrichmentProperties properties;
    private final AdminEventPublisher events;

    public SupercellKeyPoolService(SupercellApiKeyRepository repository, SupercellApiKeyRotator rotator,
                                   EnrichmentProperties properties, AdminEventPublisher events) {
        this.repository = repository;
        this.rotator = rotator;
        this.properties = properties;
        this.events = events;
    }

    /** The admin events' provider name of {@code game} (the catapult-web Keys card's). */
    static String provider(SupercellGame game) {
        return switch (game) {
            case CLASH_OF_CLANS -> AdminEventPublisher.PROVIDER_CLASHOFCLANS;
            case CLASH_ROYALE -> AdminEventPublisher.PROVIDER_CLASHROYALE;
            case BRAWL_STARS -> AdminEventPublisher.PROVIDER_BRAWLSTARS;
        };
    }

    public SupercellKeysPageData page(SupercellGame game) {
        Map<String, Long> blockedUntil = rotator.getKeyBlockedUntil();
        long now = System.currentTimeMillis();
        List<KeyStatus> keys = new ArrayList<>();
        for (SupercellApiKeyEntry entry : repository.findByExclusiveFalseWithOwner()) {
            if (SupercellGame.parse(entry.getGame()).orElse(null) != game) {
                continue;
            }
            String key = entry.getApiKey();
            String owner = entry.getOwner() != null ? entry.getOwner().getTwitchUsername() : null;
            long until = blockedUntil.getOrDefault(key, 0L);
            boolean blocked = until > now;
            long remainingSec = blocked ? TimeUnit.MILLISECONDS.toSeconds(until - now) : 0L;
            keys.add(new KeyStatus(ApiKeyHasher.id(key), label(key), owner, blocked, remainingSec,
                    rotator.usage(key)));
        }
        return new SupercellKeysPageData(keys, properties.supercellEnabled());
    }

    /** The masked key, with the IP ranges its JWT binds it to (the server's outbound address must be one). */
    static String label(String key) {
        String masked = SupercellKeys.mask(key);
        List<String> cidrs = SupercellKeys.claims(key).map(SupercellKeys.Claims::cidrs).orElse(List.of());
        return cidrs.isEmpty() ? masked : masked + " (IP " + String.join(", ", cidrs) + ")";
    }

    /** Adds the (trimmed, well-formed) key to {@code game}'s pool unless the pool has it already. */
    public void add(SupercellGame game, String apiKey) {
        if (!repository.existsById(apiKey)) {
            repository.save(new SupercellApiKeyEntry(apiKey, game.key()));
            rotator.refreshKeys();
            events.keyAdded(provider(game),
                    new KeyStatus(ApiKeyHasher.id(apiKey), label(apiKey), null, false, 0L));
        }
    }

    public void delete(SupercellGame game, String keyId) {
        Optional<String> raw = repository.findByExclusiveFalse().stream()
            .filter(e -> SupercellGame.parse(e.getGame()).orElse(null) == game)
            .map(SupercellApiKeyEntry::getApiKey)
            .filter(k -> ApiKeyHasher.id(k).equals(keyId))
            .findFirst();
        if (raw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown keyId");
        }
        repository.deleteById(raw.get());
        rotator.refreshKeys();
        events.keyDeleted(provider(game), keyId);
    }

    public void refresh(SupercellGame game) {
        rotator.refreshKeys();
        events.keysRefreshed(provider(game), page(game).keys());
    }
}
