package fr.enimaloc.catapult.event.speedrun;

import fr.enimaloc.catapult.common.dto.channel.SpeedrunData;
import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

/**
 * The owner's speedrun.com settings as they now stand: the name they declared, whether a personal key is saved (never
 * the key) and whether speedrun.com refused it, then the account used ({@code resolvedSource}: {@code api_key},
 * {@code twitch} or {@code declared}; the {@code resolved*} fields null when none is found).
 */
public record SpeedrunAccountChangedEvent(String username, String declaredUsername, boolean hasApiKey,
                                          boolean keyInvalid, String keyAccountName, String resolvedName,
                                          String resolvedUrl, String resolvedSource) implements ChannelUpdatedEvent {

    public static SpeedrunAccountChangedEvent of(String username, SpeedrunData data) {
        return new SpeedrunAccountChangedEvent(username, data.declaredUsername(), data.hasApiKey(), data.keyInvalid(),
                data.keyAccountName(), data.resolvedName(), data.resolvedUrl(), data.resolvedSource());
    }
}
