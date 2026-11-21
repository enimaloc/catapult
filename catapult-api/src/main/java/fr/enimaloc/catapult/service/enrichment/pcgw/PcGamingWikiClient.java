package fr.enimaloc.catapult.service.enrichment.pcgw;

import java.util.Optional;

/** Reads game pages from PCGamingWiki. Network failures propagate; an unusable answer is empty. */
public interface PcGamingWikiClient {

    Optional<PcGamingWikiPage> findBySteamAppId(String appId);

    /** The page whose name matches {@code name}, ignoring case and runs of spaces. */
    Optional<PcGamingWikiPage> findByName(String name);

    Optional<PcGamingWikiPage> findByPageId(long pageId);
}
