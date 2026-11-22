package fr.enimaloc.catapult.service.enrichment.rastats;

/** A RetroAchievements profile ({@code API_GetUserProfile}); {@code picUrl} is absolute, null when absent. */
public record RaUser(String user, String ulid, String picUrl, int totalPoints, int totalSoftcorePoints,
                     int totalTruePoints, String richPresence, Integer lastGameId, boolean untracked) {
}
