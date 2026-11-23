package fr.enimaloc.catapult.service.enrichment.faceit;

import com.fasterxml.jackson.annotation.JsonInclude;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.LastMatch;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.FaceitGame;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A member's personal stats on one FACEIT game, the one projection of the aggregate and of the detail route.
 * {@code status} is that of the game's own object ({@code cs2}); {@code account} is the member's FACEIT nickname.
 * {@code more} links to the detail route; the service leaves it null, the API sets it ({@link #withMore}).
 */
public record FaceitStats(Status status, FaceitGame game, String account, Cs2Stats cs2, String more) {

    /** These stats with the link to their detail route. */
    public FaceitStats withMore(String link) {
        return new FaceitStats(status, game, account, cs2, link);
    }

    /**
     * Counter-Strike 2 on FACEIT: the skill level (1 to 10), Elo and region; the lifetime numbers (each null when
     * FACEIT does not give it, all null when the lifetime stats could not be read); the last match (null when there
     * is none or it could not be read). {@code recentResults} (newest first, {@code true} a win) is filled in the
     * detail only (absent from the aggregate). Everything but {@code status} is null unless the status is ok.
     */
    @Schema(name = "FaceitCs2Stats")
    public record Cs2Stats(Status status, Integer skillLevel, Integer elo, String region, Integer matches,
                           Integer wins, Integer winRatePercent, Double averageKd, Integer averageHeadshotsPercent,
                           Integer currentWinStreak, Integer longestWinStreak, LastMatch lastMatch,
                           @JsonInclude(JsonInclude.Include.NON_NULL) List<Boolean> recentResults) {

        /** Only a status: the player or their CS2 profile is not found, or FACEIT could not be read. */
        static Cs2Stats of(Status status) {
            return new Cs2Stats(status, null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }
}
