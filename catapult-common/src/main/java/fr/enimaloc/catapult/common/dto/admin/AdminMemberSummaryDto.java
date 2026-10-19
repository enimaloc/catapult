package fr.enimaloc.catapult.common.dto.admin;

import java.util.UUID;

/** One member as catapult-api's {@code MemberSummary}: {@code twitchUsername} is null for an account without Twitch. */
public record AdminMemberSummaryDto(UUID id, String twitchUsername) {}
