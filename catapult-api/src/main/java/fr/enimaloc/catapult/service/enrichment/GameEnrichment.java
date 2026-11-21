package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.service.enrichment.hltb.TimeToBeat;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiPage;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbSummary;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckCompatibility;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbAssets;

/**
 * What the enrichment sources know of the game played now, each null when the source is off, knows nothing, failed
 * or answered too late. {@code steamAppId} is the Steam app id the Steam-keyed sources were asked with (the detected
 * one, else IGDB's mapping), null when there was none.
 */
public record GameEnrichment(TimeToBeat hltb, ProtonDbSummary protondb, SteamGridDbAssets steamgriddb,
                             SteamDeckCompatibility steamDeck, PcGamingWikiPage pcgamingwiki, Trailer trailer,
                             String steamAppId) {

    /**
     * Where to watch the game's trailer: {@code kind} {@value #MP4} for the MP4 the API rebuilds from a store's HLS
     * stream, {@value #YOUTUBE} for IGDB's first video.
     */
    public record Trailer(String kind, String url) {
        public static final String MP4 = "mp4";
        public static final String YOUTUBE = "youtube";
    }
}
