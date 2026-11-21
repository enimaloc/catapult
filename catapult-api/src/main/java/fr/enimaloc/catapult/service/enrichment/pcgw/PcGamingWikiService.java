package fr.enimaloc.catapult.service.enrichment.pcgw;

import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Cached access to PCGamingWiki pages, keyed by Steam app id, normalised name or page id. A page found by Steam app id
 * or name is also stored under its page id, so the detail route reuses it.
 */
@Service
@RequiredArgsConstructor
public class PcGamingWikiService {

    private final PcGamingWikiClient client;
    private final EnrichmentCache cache;

    public Optional<PcGamingWikiPage> bySteamAppId(String appId, FetchBudget budget) {
        return cache.get(EnrichmentSource.PCGAMINGWIKI, "steam:" + appId, PcGamingWikiPage.class, budget,
                alsoByPageId(() -> client.findBySteamAppId(appId)));
    }

    public Optional<PcGamingWikiPage> byName(String name, FetchBudget budget) {
        return cache.get(EnrichmentSource.PCGAMINGWIKI, "name:" + normalize(name), PcGamingWikiPage.class, budget,
                alsoByPageId(() -> client.findByName(name)));
    }

    public Optional<PcGamingWikiPage> byPageId(long pageId, FetchBudget budget) {
        return cache.get(EnrichmentSource.PCGAMINGWIKI, "page:" + pageId, PcGamingWikiPage.class, budget,
                () -> client.findByPageId(pageId));
    }

    /** {@code fetch}, whose answer is also stored under {@code page:{pageId}}. */
    private Supplier<Optional<PcGamingWikiPage>> alsoByPageId(Supplier<Optional<PcGamingWikiPage>> fetch) {
        return () -> {
            Optional<PcGamingWikiPage> fetched = fetch.get();
            fetched.ifPresent(page -> cache.put(EnrichmentSource.PCGAMINGWIKI, "page:" + page.pageId(), page));
            return fetched;
        };
    }

    static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
