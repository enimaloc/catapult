package fr.enimaloc.catapult.service.enrichment.steamstats;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * The Steam Web API calls behind a member's personal stats. Each call uses {@code personalKeyOrNull} when given,
 * otherwise the shared pool, and asks the rate limiter first. Anything but a clear answer is a
 * {@link SteamStatsUnavailableException}.
 */
public interface SteamStatsClient {

    /**
     * Whether Steam can be asked at all for a member with {@code personalKeyOrNull}: false when Steam is off or
     * there is neither a personal key nor a pool key. That is "nothing known", not an outage.
     */
    default boolean usable(String personalKeyOrNull) {
        return true;
    }

    /** The member's games with their play time; empty when the game list is private. */
    Optional<SteamLibrary> library(String steamId, String personalKeyOrNull);

    /** The member's achievements on one app; a private profile or an app without stats is the result's status. */
    SteamPlayerAchievements playerAchievements(String steamId, String appId, String lang, String personalKeyOrNull);

    /** The achievements an app defines; empty when Steam knows none. */
    Optional<SteamAchievementSchema> schema(String appId, String lang);

    /** The share of players (in percent) who unlocked each achievement of an app, by API name. */
    Map<String, BigDecimal> globalPercentages(String appId);
}
