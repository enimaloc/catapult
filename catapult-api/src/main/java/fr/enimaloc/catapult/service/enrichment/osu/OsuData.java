package fr.enimaloc.catapult.service.enrichment.osu;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the osu! API v2 answers, as read by {@link HttpOsuClient}: only the fields Catapult uses. No record carries a
 * token in its {@code toString}.
 */
public final class OsuData {

    /** The osu! rulesets, as osu! names them. */
    public static final Set<String> RULESETS = Set.of("osu", "taiko", "fruits", "mania");

    private OsuData() {
    }

    /** A token answer: the access token (24 h at osu!) and the refresh token that replaces the former one. */
    public record TokenGrant(String accessToken, Instant expiresAt, String refreshToken) {
        @Override
        public String toString() {
            return "TokenGrant[expiresAt=" + expiresAt + "]";
        }
    }

    /**
     * The account a token reads ({@code GET /me}): its id, username, default ruleset ({@code playmode}), country, the
     * statistics on that ruleset and, when osu! gives them, the statistics on every ruleset.
     */
    public record Me(long id, String username, String playmode, String countryCode, Statistics statistics,
                     Map<String, Statistics> rulesets) {
    }

    /**
     * A ruleset's statistics. {@code accuracy} is a percentage (0..100); {@code playTimeSeconds} the cumulative play
     * time; ranks are null when the player is not ranked.
     */
    public record Statistics(Double pp, Integer globalRank, Integer countryRank, Double accuracy, Long playCount,
                             Long playTimeSeconds, Integer level, Double levelProgress, Integer maximumCombo,
                             Long rankedScore, boolean ranked) {
    }

    /** A score: the beatmap played, its grade ({@code rank}), pp, accuracy (0..100) and when it ended. */
    public record Score(long id, String ruleset, String rank, Double pp, Double accuracy, Integer maxCombo,
                        List<String> mods, boolean passed, Instant endedAt, Beatmap beatmap) {
    }

    /** The beatmap of a score: its set's title and artist, the difficulty name and star rating, its page. */
    public record Beatmap(long id, String title, String artist, String version, Double starRating, String url) {
    }

    /** The ruleset of a {@code ruleset_id}. */
    static String ruleset(int id) {
        return switch (id) {
            case 0 -> "osu";
            case 1 -> "taiko";
            case 2 -> "fruits";
            case 3 -> "mania";
            default -> null;
        };
    }
}
