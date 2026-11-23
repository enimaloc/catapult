package fr.enimaloc.catapult.service.enrichment.hypixel;

import fr.enimaloc.catapult.getter.hypixel.HypixelApiKeyRotator;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelGames;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelPublisher.HypixelPublishedGame;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.HypixelGame;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Hypixel's public data (its games and their clean names, {@code /v2/resources/games}), through the shared cache
 * under a key that carries no member. The list needs no key, but the whole Hypixel source stays off while the key
 * pool is empty (the owner's rule), so this does too. A failed fetch is an absence, never cached.
 */
@Service
public class HypixelGameDataService {

    private final EnrichmentCache cache;
    private final HypixelClient client;
    private final HypixelApiKeyRotator keys;
    private final EnrichmentProperties properties;

    public HypixelGameDataService(EnrichmentCache cache, HypixelClient client, HypixelApiKeyRotator keys,
                                  EnrichmentProperties properties) {
        this.cache = cache;
        this.client = client;
        this.keys = keys;
        this.properties = properties;
    }

    /** Whether the Hypixel source is on: enabled, and a key in the pool. */
    public boolean available() {
        return properties.hypixelEnabled() && keys.hasKeys();
    }

    /** The games list; empty when the source is off or the list cannot be read. */
    public Optional<HypixelGames> games(FetchBudget budget) {
        if (!available() || !properties.source(EnrichmentSource.HYPIXEL_GAMES).enabled()) {
            return Optional.empty();
        }
        return cache.get(EnrichmentSource.HYPIXEL_GAMES, "games", HypixelGames.class, budget,
                () -> Optional.of(client.games()));
    }

    /** The public data of the game: the games played on the server now. */
    public Optional<HypixelPublisher> forGame(HypixelGame game, FetchBudget budget) {
        return games(budget).map(games -> {
            List<HypixelPublishedGame> current = games.games().stream()
                    .filter(g -> !g.retired() && !g.legacy())
                    .map(g -> new HypixelPublishedGame(g.type(), g.name()))
                    .toList();
            return new HypixelPublisher(game, current);
        });
    }
}
