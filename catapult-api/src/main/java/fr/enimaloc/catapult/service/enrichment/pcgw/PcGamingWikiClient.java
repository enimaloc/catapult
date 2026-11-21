package fr.enimaloc.catapult.service.enrichment.pcgw;

import java.util.Optional;

/** Reads game pages from the PCGamingWiki Cargo API. Network failures propagate; an unusable answer is empty. */
public interface PcGamingWikiClient {

    Optional<PcGamingWikiPage> findBySteamAppId(String appId);

    /** Exact (case-sensitive) wiki page name match. */
    Optional<PcGamingWikiPage> findByName(String name);

    Optional<PcGamingWikiPage> findByPageId(long pageId);
}
