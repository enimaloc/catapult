package fr.enimaloc.catapult.service.enrichment.riot;

import fr.enimaloc.catapult.getter.riot.RiotApiKeyRotator;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.RiotGame;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.Champions;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * The public Riot data (the current League of Legends patch and its champions, from Data Dragon), through the shared
 * cache; the keys carry no member. Data Dragon needs no key, but the whole Riot source stays off while the key pool
 * is empty (the owner's rule), so this does too. A failed fetch is an absence, never cached.
 */
@Service
public class RiotGameDataService {

    /** The cached shape of the current patch. */
    public record Patch(String version) {
    }

    private final EnrichmentCache cache;
    private final RiotClient client;
    private final RiotApiKeyRotator keys;
    private final EnrichmentProperties properties;

    public RiotGameDataService(EnrichmentCache cache, RiotClient client, RiotApiKeyRotator keys,
                               EnrichmentProperties properties) {
        this.cache = cache;
        this.client = client;
        this.keys = keys;
        this.properties = properties;
    }

    /** Whether the Riot source is on: enabled, and a key in the pool. */
    public boolean available() {
        return properties.riotEnabled() && keys.hasKeys();
    }

    /** The public data of {@code game}; empty when the source is off, Data Dragon fails, or there is none. */
    public Optional<RiotPublisher> forGame(RiotGame game, FetchBudget budget) {
        if (game != RiotGame.LOL) {
            return Optional.empty();
        }
        return patch(budget).map(patch -> new RiotPublisher(game, new RiotPublisher.Lol(patch)));
    }

    /** The current League of Legends patch. */
    public Optional<String> patch(FetchBudget budget) {
        if (!available()) {
            return Optional.empty();
        }
        return cache.get(EnrichmentSource.RIOT_LOL_PATCH, "lol-patch", Patch.class, budget,
                () -> Optional.of(new Patch(client.latestVersion()))).map(Patch::version);
    }

    /** The champions of the current patch, named in {@code locale}'s language. */
    public Optional<Champions> champions(Locale locale, FetchBudget budget) {
        Optional<String> patch = patch(budget);
        if (patch.isEmpty()) {
            return Optional.empty();
        }
        String ddragonLocale = RiotLocale.of(locale);
        return cache.get(EnrichmentSource.RIOT_LOL_CHAMPIONS, "lol-champions:" + patch.get() + ":" + ddragonLocale,
                Champions.class, budget, () -> Optional.of(client.champions(patch.get(), ddragonLocale)));
    }
}
