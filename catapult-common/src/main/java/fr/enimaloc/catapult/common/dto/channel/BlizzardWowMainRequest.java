package fr.enimaloc.catapult.common.dto.channel;

/** The imported World of Warcraft character a member picks as their main. */
public record BlizzardWowMainRequest(String region, long characterId) {}
