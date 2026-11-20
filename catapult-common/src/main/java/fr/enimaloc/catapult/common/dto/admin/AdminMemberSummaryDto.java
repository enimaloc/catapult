package fr.enimaloc.catapult.common.dto.admin;

import java.util.UUID;

/**
 * A member by id and Twitch login, as the admin pages name it: {@code twitchUsername} is null for an account without
 * Twitch. {@code dryRun}: Catapult writes nothing public on the member's Twitch channel; boxed so an older API that
 * doesn't send it reads as false.
 */
public record AdminMemberSummaryDto(UUID id, String twitchUsername, Boolean dryRun) {

    public AdminMemberSummaryDto {
        dryRun = dryRun != null && dryRun;
    }

    /** A member out of dry run, for the lists that don't tell. */
    public AdminMemberSummaryDto(UUID id, String twitchUsername) {
        this(id, twitchUsername, false);
    }
}
