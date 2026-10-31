package fr.enimaloc.catapult.common.dto.admin;

/** A Twitch category as catapult-api's category search lists it ({@code boxArtUrl} may be null). */
public record TwitchCategoryDto(String id, String name, String boxArtUrl) {}
