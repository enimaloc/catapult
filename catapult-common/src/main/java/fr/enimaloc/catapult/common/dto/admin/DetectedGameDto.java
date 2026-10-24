package fr.enimaloc.catapult.common.dto.admin;

/**
 * A game detected at streamers' (platform + its ID), for the admin game mappings: its most frequent detected name
 * and how many streamers have it.
 */
public record DetectedGameDto(String sourceType, String sourceId, String sourceName, long streamerCount) {}
