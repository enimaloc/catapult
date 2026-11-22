package fr.enimaloc.catapult.service.enrichment.rastats;

import java.time.Instant;
import java.util.List;

/**
 * A game and a member's progress in it ({@code API_GetGameInfoAndUserProgress}). {@code achievements} are in the
 * game's display order. {@code userCompletion*} are the site's text (such as {@code "66.67%"}), and
 * {@code userTotalPlaytime} its number (unit not verified).
 */
public record RaGameProgress(int gameId, String title, String consoleName, String iconUrl, int numAchievements,
                             int numDistinctPlayers, int numDistinctPlayersHardcore, int numAwardedToUser,
                             int numAwardedToUserHardcore, String userCompletion, String userCompletionHardcore,
                             Integer userTotalPlaytime, String highestAwardKind, Instant highestAwardDate,
                             List<RaAchievement> achievements) {
}
