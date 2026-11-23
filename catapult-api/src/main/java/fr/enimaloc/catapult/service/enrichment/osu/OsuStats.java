package fr.enimaloc.catapult.service.enrichment.osu;

import com.fasterxml.jackson.annotation.JsonInclude;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.OsuGame;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A member's personal stats on osu!, the one projection of the aggregate and of the detail route. {@code status} is
 * that of {@code osu}. {@code account} is the osu! username. {@code osu.rulesets} and {@code osu.recentScores} are
 * filled in the detail only (absent from the aggregate). {@code more} links to the detail route; the service leaves
 * it null, the API sets it ({@link #withMore}).
 */
@Schema(name = "OsuStats")
public record OsuStats(Status status, OsuGame game, String account, Profile osu, String more) {

    /** These stats with the link to their detail route. */
    public OsuStats withMore(String link) {
        return new OsuStats(status, game, account, osu, link);
    }

    /**
     * The osu! profile on the member's default ruleset ({@code osu}, {@code taiko}, {@code fruits}, {@code mania});
     * everything but {@code status} is null unless it is ok. {@code accuracy} is a percentage, {@code playTimeSeconds}
     * the cumulative play time, ranks are null when the player is not ranked. {@code lastScore} is the latest passed
     * play on that ruleset (null when none, or when it could not be read in time).
     */
    @Schema(name = "OsuProfile")
    public record Profile(Status status, String ruleset, Double pp, Integer globalRank, Integer countryRank,
                          String country, Double accuracy, Long playCount, Long playTimeSeconds, Integer level,
                          Score lastScore,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, RulesetStats> rulesets,
                          @JsonInclude(JsonInclude.Include.NON_NULL) List<Score> recentScores) {

        /** Only a status. */
        public static Profile of(Status status) {
            return new Profile(status, null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    /** A ruleset's numbers, in the detail only. */
    @Schema(name = "OsuRulesetStats")
    public record RulesetStats(Double pp, Integer globalRank, Integer countryRank, Double accuracy, Long playCount,
                               Long playTimeSeconds, Integer level) {
    }

    /**
     * A score: the beatmap ({@code title} by {@code artist}, difficulty {@code version}, star rating, page), its grade
     * ({@code rank}: {@code XH}, {@code X}, {@code SH}, {@code S}, {@code A}…), pp (null when unranked), accuracy
     * (a percentage), max combo, mods (acronyms) and when it ended.
     */
    @Schema(name = "OsuScore")
    public record Score(String title, String artist, String version, Double starRating, String beatmapUrl,
                        String rank, Double pp, Double accuracy, Integer maxCombo, List<String> mods,
                        Instant endedAt) {
    }
}
