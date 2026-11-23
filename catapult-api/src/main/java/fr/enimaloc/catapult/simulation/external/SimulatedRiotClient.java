package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.riot.RiotClient;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.Champion;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.Champions;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.LeagueEntry;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.LiveGame;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.LolSummoner;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.RiotAccountRef;
import fr.enimaloc.catapult.service.enrichment.riot.RiotNotFoundException;
import fr.enimaloc.catapult.service.enrichment.riot.RiotPlatform;
import fr.enimaloc.catapult.service.enrichment.riot.RiotPlatform.Cluster;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Riot in the mock profile: every Riot ID exists (its PUUID is derived from it) except a game name {@code ghost},
 * which Riot does not know; each summoner is level 312, Gold II in solo queue, playing Aatrox now, and Platinum IV in
 * Teamfight Tactics. Data Dragon answers patch 16.23.1 with three champions.
 */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedRiotClient implements RiotClient {

    static final String VERSION = "16.23.1";

    @Override
    public RiotAccountRef accountByRiotId(Cluster cluster, String gameName, String tagLine) {
        if (gameName.toLowerCase(Locale.ROOT).equals("ghost")) {
            throw new RiotNotFoundException("simulated: no such Riot ID");
        }
        return new RiotAccountRef("sim-" + gameName.toLowerCase(Locale.ROOT) + "-" + tagLine.toLowerCase(Locale.ROOT),
                gameName, tagLine, "simulated");
    }

    @Override
    public LolSummoner lolSummoner(RiotPlatform platform, String puuid, String keyId) {
        return new LolSummoner(312, 6);
    }

    @Override
    public List<LeagueEntry> lolLeague(RiotPlatform platform, String puuid, String keyId) {
        return List.of(new LeagueEntry("RANKED_SOLO_5x5", "GOLD", "II", 57, 61, 54),
                new LeagueEntry("RANKED_FLEX_SR", "SILVER", "I", 12, 10, 9));
    }

    @Override
    public Optional<LiveGame> lolActiveGame(RiotPlatform platform, String puuid, String keyId) {
        return Optional.of(new LiveGame("CLASSIC", "MATCHED", 420, 266,
                Instant.parse("2026-11-23T18:00:00Z"), 754L));
    }

    @Override
    public List<LeagueEntry> tftLeague(RiotPlatform platform, String puuid, String keyId) {
        return List.of(new LeagueEntry("RANKED_TFT", "PLATINUM", "IV", 3, 22, 71));
    }

    @Override
    public String latestVersion() {
        return VERSION;
    }

    @Override
    public Champions champions(String version, String locale) {
        return new Champions(version, Map.of("266", new Champion("Aatrox", "Aatrox"),
                "103", new Champion("Ahri", "Ahri"), "62", new Champion("MonkeyKing", "Wukong")));
    }
}
