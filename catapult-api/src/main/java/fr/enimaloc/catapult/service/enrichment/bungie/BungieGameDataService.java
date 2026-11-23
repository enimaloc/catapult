package fr.enimaloc.catapult.service.enrichment.bungie;

import fr.enimaloc.catapult.service.bungie.BungieCredentialsService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData.Definition;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BungieGame;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * The public Bungie data (the Destiny 2 season in progress, the names of the manifest's definitions), through the
 * shared cache. Nothing here depends on a member: the keys carry no member. A failed fetch is an absence, never
 * cached. Off (empty) while no Bungie application is set.
 */
@Service
public class BungieGameDataService {

    private final EnrichmentCache cache;
    private final BungieClient client;
    private final BungieCredentialsService credentials;

    public BungieGameDataService(EnrichmentCache cache, BungieClient client, BungieCredentialsService credentials) {
        this.cache = cache;
        this.client = client;
        this.credentials = credentials;
    }

    /** Whether the Bungie source is on (an application is set). */
    public boolean available() {
        return credentials.available();
    }

    /** The public data of {@code game}; empty when the source is off or Bungie fails. */
    public Optional<BungiePublisher> forGame(BungieGame game, Locale locale, FetchBudget budget) {
        if (game != BungieGame.DESTINY2 || !available()) {
            return Optional.empty();
        }
        String lc = BungieData.locale(locale);
        return cache.get(EnrichmentSource.BUNGIE_SEASON, "season:" + lc, BungiePublisher.Season.class, budget,
                () -> {
                    BungieData.Season season = client.season(client.currentSeasonHash(), lc);
                    return Optional.of(new BungiePublisher.Season(season.number(), season.name(), season.startsAt(),
                            season.endsAt()));
                })
                .map(season -> new BungiePublisher(game, season));
    }

    /** The name of the manifest definition {@code hash} of {@code entity}; empty when unknown or not readable now. */
    public Optional<String> definitionName(String entity, long hash, String lc, FetchBudget budget) {
        if (hash == 0 || !available()) {
            return Optional.empty();
        }
        return cache.get(EnrichmentSource.BUNGIE_DEFINITIONS, "def:" + entity + ":" + Long.toUnsignedString(hash)
                        + ":" + lc, Definition.class, budget, () -> {
                    try {
                        return Optional.of(client.definition(entity, hash, lc));
                    } catch (BungieExceptions.NotFound e) {
                        return Optional.empty();
                    }
                })
                .map(Definition::name);
    }
}
