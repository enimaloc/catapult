package fr.enimaloc.catapult.service.enrichment.faceit;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** What the FACEIT Data API answers, as read by {@link HttpFaceitClient}; also the shapes kept in the cache. */
public final class FaceitModels {

    private FaceitModels() {
    }

    /**
     * A FACEIT player: the stable id, the nickname as FACEIT spells it, the avatar, country and profile page, and
     * their Counter-Strike 2 profile ({@code cs2}, null when they have none).
     */
    @Schema(name = "FaceitPlayer")
    public record Player(String playerId, String nickname, String avatar, String country, String faceitUrl,
                         GameProfile cs2) {
    }

    /** A player's profile on one FACEIT game: skill level (1 to 10), Elo and matchmaking region. */
    @Schema(name = "FaceitGameProfile")
    public record GameProfile(Integer skillLevel, Integer elo, String region) {
    }

    /**
     * A player's lifetime stats on one game, as FACEIT names them ({@code Matches}, {@code Wins}, {@code Win Rate %},
     * {@code Average K/D Ratio}, {@code Average Headshots %}, {@code Current Win Streak}, {@code Longest Win Streak},
     * {@code Recent Results}); each null when FACEIT does not give it. {@code recentResults} is newest first
     * ({@code true} a win).
     */
    @Schema(name = "FaceitLifetime")
    public record Lifetime(Integer matches, Integer wins, Integer winRatePercent, Double averageKd,
                           Integer averageHeadshotsPercent, Integer currentWinStreak, Integer longestWinStreak,
                           List<Boolean> recentResults) {

        public Lifetime {
            recentResults = recentResults == null ? List.of() : List.copyOf(recentResults);
        }
    }

    /** The player's last match on one game: its id, map, score ({@code 13 / 9}), result and their own numbers. */
    @Schema(name = "FaceitLastMatch")
    public record LastMatch(String matchId, String map, String score, Boolean won, Integer kills, Integer deaths,
                            Double kd, Instant finishedAt) {
    }
}
