package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * An admin game mapping as catapult-api lists it: a detected game (platform + its ID) set to a Twitch category for
 * every streamer. {@code ccls} / {@code tws} are null when the bindings keep IGDB's, else the admin's (even empty).
 * {@code sourceName} is the game's most frequent detected name, null when it was never detected;
 * {@code bindingCount} the number of bindings that follow it (MAPPED); {@code updatedBy} the admin's Twitch login.
 * {@code ignoredByDefault}: the game's bindings are ignored, but those whose streamer chose; its category
 * ({@code twitchGameId}, {@code twitchGameName}) is then null when it sets none.
 */
public record GameMappingDto(UUID id, String sourceType, String sourceId, String sourceName, String twitchGameId,
                             String twitchGameName, Set<String> ccls, Set<String> tws, long bindingCount,
                             Instant updatedAt, String updatedBy, Boolean ignoredByDefault) {

    /** Boxed so a catapult-api that predates it still reads (Jackson refuses a missing primitive): absent, false. */
    public GameMappingDto {
        ignoredByDefault = Boolean.TRUE.equals(ignoredByDefault);
    }

    /** A mapping that doesn't ignore its game. */
    public GameMappingDto(UUID id, String sourceType, String sourceId, String sourceName, String twitchGameId,
                          String twitchGameName, Set<String> ccls, Set<String> tws, long bindingCount,
                          Instant updatedAt, String updatedBy) {
        this(id, sourceType, sourceId, sourceName, twitchGameId, twitchGameName, ccls, tws, bindingCount, updatedAt,
                updatedBy, false);
    }
}
