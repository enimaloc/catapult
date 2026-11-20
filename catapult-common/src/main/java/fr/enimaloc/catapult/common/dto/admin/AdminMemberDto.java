package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A member as the admin members page lists it: the fields of catapult-api's UserAccount it shows,
 * the rest (tokens, timestamps) left out. {@code debug} is the member's debug mode, null when it was never switched on. {@code status} is ACTIVE, INACTIVE or PENDING_DELETION. {@code premium} is false once the end date has passed; {@code premiumUntil} is the stored last premium day.
 * {@code dryRun}: Catapult writes nothing public on the member's Twitch channel.
 */
public record AdminMemberDto(UUID id, String twitchId, String twitchUsername, String profileImageUrl,
                             String steamId, boolean systemAccount, boolean botEnabled, String status,
                             Instant createdAt, MemberDebugStateDto debug,
                             Boolean premium, LocalDate premiumUntil, Boolean dryRun) {

    /**
     * A boxed {@code premium} and {@code dryRun} so an older API that doesn't send them reads as false rather than
     * failing the whole page.
     */
    public AdminMemberDto {
        premium = premium != null && premium;
        dryRun = dryRun != null && dryRun;
    }

    /** A member out of dry run, for the callers that don't know about it. */
    public AdminMemberDto(UUID id, String twitchId, String twitchUsername, String profileImageUrl,
                          String steamId, boolean systemAccount, boolean botEnabled, String status,
                          Instant createdAt, MemberDebugStateDto debug,
                          Boolean premium, LocalDate premiumUntil) {
        this(id, twitchId, twitchUsername, profileImageUrl, steamId, systemAccount, botEnabled, status, createdAt,
                debug, premium, premiumUntil, false);
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
