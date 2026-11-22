package fr.enimaloc.catapult.service.enrichment.blizzard;

import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BlizzardGame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The public Blizzard data (WoW Token price, Hearthstone sets and decks, Diablo III season), through the shared
 * cache. Nothing here depends on a member: the keys carry no member. A failed fetch is an absence, never cached.
 */
@Slf4j
@Service
public class BlizzardGameDataService {

    /** The cached shape of the Hearthstone sets (the cache stores one object per key). */
    public record Sets(List<HearthstoneSet> sets) {
        public Sets {
            sets = List.copyOf(sets);
        }
    }

    /** The cached shape of the current Diablo III season. */
    public record Season(int number) {
    }

    private final EnrichmentCache cache;
    private final BlizzardClient client;
    private final BlizzardAppTokenService tokens;

    @Autowired
    public BlizzardGameDataService(EnrichmentCache cache, BlizzardClient client, BlizzardAppTokenService tokens) {
        this.cache = cache;
        this.client = client;
        this.tokens = tokens;
    }

    /** The public data of {@code game}; empty when the source is off, Blizzard fails, or there is none (SC2). */
    public Optional<BlizzardPublisher> forGame(BlizzardGame game, BlizzardRegion region, Locale locale,
                                               FetchBudget budget) {
        if (!tokens.available()) {
            return Optional.empty();
        }
        return switch (game) {
            case WOW -> wowToken(region, budget).map(price -> new BlizzardPublisher(game, region.key(),
                    new BlizzardPublisher.Wow(price.gold(), price.updatedAt()), null, null, null));
            case HEARTHSTONE -> latestSet(BlizzardLocale.of(locale, region), budget).map(set ->
                    new BlizzardPublisher(game, region.key(), null, new BlizzardPublisher.Hearthstone(set), null,
                            null));
            case D3 -> cached(EnrichmentSource.BLIZZARD_D3_SEASON, "d3-season:" + region.key(), Season.class,
                    budget, () -> new Season(client.d3CurrentSeason(region)))
                    .map(season -> new BlizzardPublisher(game, region.key(), null, null,
                            new BlizzardPublisher.D3(season.number()), null));
            case SC2 -> Optional.empty();
        };
    }

    /** A deck from its code. */
    public Optional<HearthstoneDeck> hearthstoneDeck(String code, Locale locale, FetchBudget budget) {
        if (!tokens.available()) {
            return Optional.empty();
        }
        String blizzardLocale = BlizzardLocale.of(locale, BlizzardRegion.EU);
        return cached(EnrichmentSource.BLIZZARD_HS_DECK, "hs-deck:" + code + ":" + blizzardLocale,
                HearthstoneDeck.class, budget, () -> client.hearthstoneDeck(code, blizzardLocale));
    }

    /** The WoW Token price of {@code region}. */
    public Optional<WowTokenPrice> wowToken(BlizzardRegion region, FetchBudget budget) {
        if (!tokens.available()) {
            return Optional.empty();
        }
        return cached(EnrichmentSource.BLIZZARD_WOW_TOKEN, "wow-token:" + region.key(), WowTokenPrice.class,
                budget, () -> client.wowToken(region));
    }

    /** The first set (Blizzard lists the newest first) that has collectible cards. */
    private Optional<HearthstoneSet> latestSet(String locale, FetchBudget budget) {
        return cached(EnrichmentSource.BLIZZARD_HS_SETS, "hs-sets:" + locale, Sets.class, budget,
                () -> new Sets(client.hearthstoneSets(locale)))
                .flatMap(all -> all.sets().stream()
                        .filter(s -> s.collectibleCount() != null && s.collectibleCount() > 0).findFirst());
    }

    private <T> Optional<T> cached(EnrichmentSource source, String key, Class<T> type, FetchBudget budget,
                                   Supplier<T> fetch) {
        return cache.get(source, key, type, budget, () -> {
            try {
                return Optional.ofNullable(fetch.get());
            } catch (BlizzardNotFoundException e) {
                // A public datum that Blizzard does not know (a deck code): known absent
                return Optional.empty();
            }
        });
    }
}
