package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;
import java.util.UUID;

/**
 * A member as the admin members page lists it: the fields of catapult-api's UserAccount it shows,
 * the rest (tokens, timestamps) left out. {@code status} is ACTIVE, INACTIVE or PENDING_DELETION.
 */
public record AdminMemberDto(UUID id, String twitchId, String twitchUsername, String profileImageUrl,
                             String steamId, boolean systemAccount, boolean botEnabled, String status,
                             Instant createdAt) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
