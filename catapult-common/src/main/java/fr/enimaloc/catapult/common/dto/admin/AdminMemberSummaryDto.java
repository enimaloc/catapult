package fr.enimaloc.catapult.common.dto.admin;

import java.util.UUID;

/** A member by id and Twitch login, as the admin pages name it: {@code twitchUsername} is null for an account without Twitch. */
public record AdminMemberSummaryDto(UUID id, String twitchUsername) {}
