package fr.enimaloc.catapult.common.dto.admin;

import java.util.Set;

/**
 * Creates or changes an admin game mapping. {@code ccls} / {@code tws}: null keeps IGDB's, a set (even empty)
 * replaces them. {@code applyLive}: also update now the channels live on this game, else their bindings only serve
 * the next detection.
 */
public record GameMappingRequest(String sourceType, String sourceId, String twitchGameId, String twitchGameName,
                                 Set<String> ccls, Set<String> tws, boolean applyLive) {}
