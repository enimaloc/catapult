package fr.enimaloc.catapult.common.dto.admin;

import java.util.Set;

/**
 * Creates or changes an admin game mapping. {@code ccls} / {@code tws}: null keeps IGDB's, a set (even empty)
 * replaces them. {@code applyLive}: also update now the channels live on this game, else their bindings only serve
 * the next detection. {@code ignoredByDefault}: the game's bindings are ignored, but those whose streamer chose; the
 * category is then optional (none: the bindings keep IGDB's).
 */
public record GameMappingRequest(String sourceType, String sourceId, String twitchGameId, String twitchGameName,
                                 Set<String> ccls, Set<String> tws, boolean applyLive, Boolean ignoredByDefault) {

    /** Boxed so a body without it still reads (Jackson refuses a missing primitive): absent, the game isn't ignored. */
    public GameMappingRequest {
        ignoredByDefault = Boolean.TRUE.equals(ignoredByDefault);
    }

    /** A request that doesn't ignore the game. */
    public GameMappingRequest(String sourceType, String sourceId, String twitchGameId, String twitchGameName,
                              Set<String> ccls, Set<String> tws, boolean applyLive) {
        this(sourceType, sourceId, twitchGameId, twitchGameName, ccls, tws, applyLive, false);
    }
}
