package fr.enimaloc.catapult.service.enrichment.riot;

import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.Champions;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.LeagueEntry;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.LiveGame;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.LolSummoner;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.RiotAccountRef;
import fr.enimaloc.catapult.service.enrichment.riot.RiotPlatform.Cluster;

import java.util.List;
import java.util.Optional;

/**
 * The Riot API (with a key of the shared pool) and Data Dragon (no key). Every request is charged to the
 * {@link RiotRateLimiter}. Failures are {@link RiotUnavailableException}; only Riot's JSON 404 is
 * {@link RiotNotFoundException}.
 */
public interface RiotClient {

    /** The account of a Riot ID (account-v1), asked of {@code cluster}'s account host. */
    RiotAccountRef accountByRiotId(Cluster cluster, String gameName, String tagLine);

    LolSummoner lolSummoner(RiotPlatform platform, String puuid);

    /** The ranked queues of the summoner; empty when unranked. */
    List<LeagueEntry> lolLeague(RiotPlatform platform, String puuid);

    /** The game the summoner is in now; empty when they are in none (Riot's JSON 404). */
    Optional<LiveGame> lolActiveGame(RiotPlatform platform, String puuid);

    /** The Teamfight Tactics ranked queues; empty when unranked. */
    List<LeagueEntry> tftLeague(RiotPlatform platform, String puuid);

    /** The current League of Legends patch, from Data Dragon (no key). */
    String latestVersion();

    /** The champions of a patch in a Data Dragon locale ({@code en_US}…), by their numeric key. */
    Champions champions(String version, String locale);
}
