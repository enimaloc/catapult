package fr.enimaloc.catapult.service.enrichment.riot;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The shapes read from the Riot API and Data Dragon. */
public final class RiotModels {

    private RiotModels() {
    }

    /**
     * A Riot account (account-v1): its PUUID, the Riot ID as Riot spells it, and the id ({@code ApiKeyHasher.id}) of
     * the key that resolved it, the only key the PUUID is valid with (Riot encrypts its ids per key project).
     */
    public record RiotAccountRef(String puuid, String gameName, String tagLine, String keyId) {
    }

    /** A League of Legends summoner (summoner-v4). */
    public record LolSummoner(int level, Integer profileIconId) {
    }

    /** A ranked queue entry (league-v4, TFT league-v1). */
    public record LeagueEntry(String queueType, String tier, String rank, int leaguePoints, int wins, int losses) {
    }

    /** The League of Legends game the member is in now (spectator-v5); {@code championId} is theirs. */
    public record LiveGame(String gameMode, String gameType, Integer queueId, Integer championId, Instant startedAt,
                           Long lengthSeconds) {
    }

    /** A champion of Data Dragon: its id (the image name), its name in the asked locale. */
    public record Champion(String id, String name) {
    }

    /** The cached shape of the champions of one patch, by their numeric key. */
    public record Champions(String version, Map<String, Champion> byKey) {
        public Champions {
            byKey = Map.copyOf(byKey);
        }
    }

    /** The cached shape of a ranked queue list. */
    public record Leagues(List<LeagueEntry> entries) {
        public Leagues {
            entries = List.copyOf(entries);
        }
    }
}
