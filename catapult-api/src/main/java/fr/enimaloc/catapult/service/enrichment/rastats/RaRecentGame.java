package fr.enimaloc.catapult.service.enrichment.rastats;

import java.time.Instant;

/** A game of {@code API_GetUserRecentlyPlayedGames}; {@code lastPlayed} is null when unreadable. */
public record RaRecentGame(int gameId, String consoleName, String title, String iconUrl, Instant lastPlayed,
                           int achievementsTotal, int numAchieved, int numAchievedHardcore, int scoreAchieved,
                           int scoreAchievedHardcore, int possibleScore) {
}
