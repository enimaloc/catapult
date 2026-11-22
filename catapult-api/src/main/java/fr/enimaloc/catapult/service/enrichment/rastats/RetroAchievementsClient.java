package fr.enimaloc.catapult.service.enrichment.rastats;

import java.util.List;
import java.util.Optional;

/**
 * The RetroAchievements web API. Every call first asks the limiter's budget, then sends the key as the {@code y}
 * query parameter (the API knows no header). A 401 is {@link RaKeyRejectedException} (the pool's key is then set
 * aside; a personal one is for the caller), a 429 pauses the source, anything else unexpected is
 * {@link RaUnavailableException}; a budget spent is {@code UpstreamBudgetExceededException}.
 */
public interface RetroAchievementsClient {

    /** The profile of a name or ULID; empty when RetroAchievements does not know it. */
    Optional<RaUser> profile(String userOrUlid, RaCredentials credentials);

    List<RaRecentGame> recentlyPlayed(String ulid, int count, RaCredentials credentials);

    /** The game with the member's progress, achievements in display order; empty when the game is unknown. */
    Optional<RaGameProgress> progress(int gameId, String ulid, RaCredentials credentials);

    /** The achievements unlocked in the last {@code minutes}. */
    List<RaAchievement> recentAchievements(String ulid, int minutes, RaCredentials credentials);
}
