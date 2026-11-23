package fr.enimaloc.catapult.service.enrichment.rastats;

import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;

import java.util.List;

/**
 * A member's RetroAchievements account as {@code /api/me/stats/retroachievements} serves it: the declared name, the
 * profile's points and rich presence, the games played lately and the achievements of the last 24 hours.
 * {@code status} is {@code ok}, {@code private} (the account is untracked: no points nor lists) or
 * {@code unavailable} (RetroAchievements could not be read: nothing but the name). A list is null when it could not
 * be read, its source being off included.
 */
public record RaAccountView(Status status, String user, String profileUrl, String picUrl, Integer totalPoints,
                            Integer totalSoftcorePoints, Integer totalTruePoints, String richPresence,
                            Integer lastGameId, List<RaRecentGame> recentlyPlayed,
                            List<RaAchievement> recentAchievements) {
}
