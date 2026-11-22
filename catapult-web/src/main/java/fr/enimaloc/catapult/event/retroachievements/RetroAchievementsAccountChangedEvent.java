package fr.enimaloc.catapult.event.retroachievements;

import fr.enimaloc.catapult.common.dto.channel.RetroAchievementsData;
import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

/**
 * The owner's RetroAchievements settings as they now stand: the name they declared (null for none), the link to its
 * RetroAchievements page, whether a personal key is saved (never the key), whether RetroAchievements refused it and
 * whether Catapult has a shared key to fall back on.
 */
public record RetroAchievementsAccountChangedEvent(String username, String declaredUsername, String profileUrl,
                                                   boolean hasApiKey, boolean keyInvalid, boolean poolAvailable)
        implements ChannelUpdatedEvent {

    public static RetroAchievementsAccountChangedEvent of(String username, RetroAchievementsData data) {
        return new RetroAchievementsAccountChangedEvent(username, data.username(), data.profileUrl(),
                data.hasApiKey(), data.keyInvalid(), data.poolAvailable());
    }
}
