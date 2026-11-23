package fr.enimaloc.catapult.common.dto.channel;

/**
 * The owner's RetroAchievements account, for the settings card: the name they declared and its profile page, whether
 * a personal API key is saved (never the key itself), whether RetroAchievements refused it at its last use, and
 * whether the shared key pool can serve a member without a usable key of their own.
 */
public record RetroAchievementsData(
        String username,
        String profileUrl,
        boolean hasApiKey,
        boolean keyInvalid,
        boolean poolAvailable
) {}
