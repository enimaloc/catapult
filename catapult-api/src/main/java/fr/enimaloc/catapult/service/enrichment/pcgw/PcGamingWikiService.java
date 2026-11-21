package fr.enimaloc.catapult.service.enrichment.pcgw;

import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/** Cached access to PCGamingWiki pages, keyed by Steam app id, normalised name or page id. */
@Service
@RequiredArgsConstructor
public class PcGamingWikiService {

    private final PcGamingWikiClient client;
    private final EnrichmentCache cache;

    public Optional<PcGamingWikiPage> bySteamAppId(String appId, FetchBudget budget) {
        return cache.get(EnrichmentSource.PCGAMINGWIKI, "steam:" + appId, PcGamingWikiPage.class, budget,
                () -> client.findBySteamAppId(appId));
    }

    public Optional<PcGamingWikiPage> byName(String name, FetchBudget budget) {
        return cache.get(EnrichmentSource.PCGAMINGWIKI, "name:" + normalize(name), PcGamingWikiPage.class, budget,
                () -> client.findByName(name));
    }

    public Optional<PcGamingWikiPage> byPageId(long pageId, FetchBudget budget) {
        return cache.get(EnrichmentSource.PCGAMINGWIKI, "page:" + pageId, PcGamingWikiPage.class, budget,
                () -> client.findByPageId(pageId));
    }

    static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
