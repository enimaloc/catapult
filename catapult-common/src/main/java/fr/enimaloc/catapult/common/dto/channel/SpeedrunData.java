package fr.enimaloc.catapult.common.dto.channel;

/**
 * The owner's speedrun.com account, for the settings card: the name they declared, whether a personal key is saved
 * (never the key itself), whether speedrun.com refused it at its last check and whose account it proved, then the
 * account actually used ({@code resolvedSource}: {@code api_key}, {@code twitch} or {@code declared}; all three
 * {@code resolved*} null when none is found).
 */
public record SpeedrunData(
        String declaredUsername,
        boolean hasApiKey,
        boolean keyInvalid,
        String keyAccountName,
        String resolvedName,
        String resolvedUrl,
        String resolvedSource
) {}
