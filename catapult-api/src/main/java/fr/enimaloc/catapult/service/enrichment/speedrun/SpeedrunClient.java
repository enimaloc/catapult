package fr.enimaloc.catapult.service.enrichment.speedrun;

import java.util.List;
import java.util.Optional;

/**
 * Reads speedrun.com (API v1). The caller's budget is asked by the cache before the call; a 420 or 429 answer
 * pauses the {@link SpeedrunRateLimiter} and throws {@link SpeedrunUnavailableException}, as does a 5xx.
 */
public interface SpeedrunClient {

    /** Games whose name looks like {@code name} (the search is approximate), best first. */
    List<SpeedrunGame> searchGames(String name);

    /** The world records of the main full-game categories; empty when the game is unknown. */
    Optional<SpeedrunRecords> records(String gameId);

    /** The accounts whose profile links the Twitch login {@code login} (exact, case-insensitive). */
    List<SpeedrunUser> usersByTwitch(String login);

    /** The account named or identified by {@code nameOrId}; empty when there is none. */
    Optional<SpeedrunUser> user(String nameOrId);

    /** The account of a personal API key, sent in a header only; empty when the key is refused (403). */
    Optional<SpeedrunUser> profile(String apiKey);

    /** The personal bests of an account, full-game only (runs of a level are dropped); one game or all of them. */
    List<SpeedrunRun> personalBests(String userId, String gameIdOrNull);

    /** The runs of an account waiting for verification, full-game only; the key (may be null) is sent as is. */
    List<SpeedrunRun> pendingRuns(String userId, String gameIdOrNull, String apiKeyOrNull);
}
