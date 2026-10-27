package fr.enimaloc.catapult.common.dto.account;

import java.util.UUID;

/** An account found by its Twitch username: its id, the {@code userId} of its routes. */
public record UserRefDto(UUID id, String twitchUsername) {}
