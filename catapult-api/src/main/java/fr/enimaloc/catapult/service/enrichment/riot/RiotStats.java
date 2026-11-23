package fr.enimaloc.catapult.service.enrichment.riot;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.RiotGame;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;

import java.time.Instant;
import java.util.List;

/**
 * A member's personal stats on one Riot game, the one projection of the aggregate and of the detail route.
 * {@code status} is that of the game's own object ({@code lol} or {@code tft}; Valorant is {@code unsupported},
 * without an object); the other is null. {@code queues} is filled in the detail only (absent from the aggregate).
 * {@code more} links to the detail route; the service leaves it null, the API sets it ({@link #withMore}).
 */
public record RiotStats(Status status, RiotGame game, String account, String platform, LolStats lol, TftStats tft,
                        String more) {

    /** These stats with the link to their detail route. */
    public RiotStats withMore(String link) {
        return new RiotStats(status, game, account, platform, lol, tft, link);
    }

    /**
     * League of Legends: the summoner level and icon; {@code ranked} null when the ranked queues could not be read
     * (its {@code solo} and {@code flex} are null when unranked there); {@code inGame} null when unknown, and the
     * {@code currentGame} when the member is in one.
     */
    @Schema(name = "RiotLolStats")
    public record LolStats(Status status, Integer level, String profileIconUrl, Ranked ranked, Boolean inGame,
                           CurrentGame currentGame,
                           @JsonInclude(JsonInclude.Include.NON_NULL) List<Queue> queues) {
    }

    /** The solo / duo and flex ranks. */
    @Schema(name = "RiotRanked")
    public record Ranked(Rank solo, Rank flex) {
    }

    /** A rank: tier ({@code GOLD}…), division ({@code II}; null for the apex tiers' single one), LP, wins, losses. */
    @Schema(name = "RiotRank")
    public record Rank(String tier, String division, int leaguePoints, int wins, int losses) {
    }

    /** One ranked queue as Riot names it ({@code RANKED_SOLO_5x5}…), for the detail. */
    @Schema(name = "RiotQueue")
    public record Queue(String queueType, Rank rank) {
    }

    /**
     * The game the member is in now: their champion (name in the asked language, Data Dragon image; null when the
     * champion list could not be read), the game mode, the queue id, and when it started.
     */
    @Schema(name = "RiotCurrentGame")
    public record CurrentGame(Integer championId, String champion, String championIconUrl, String gameMode,
                              Integer queueId, Instant startedAt, Long lengthSeconds) {
    }

    /** Teamfight Tactics: the ranked rank, null when unranked. */
    @Schema(name = "RiotTftStats")
    public record TftStats(Status status, Rank rank,
                           @JsonInclude(JsonInclude.Include.NON_NULL) List<Queue> queues) {
    }
}
