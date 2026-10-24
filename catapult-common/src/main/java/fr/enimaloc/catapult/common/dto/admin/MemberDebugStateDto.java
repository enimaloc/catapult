package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;
import java.util.UUID;

/**
 * A member's debug mode as the admin sees it. {@code purgeAt} is when the recorded exchanges go
 * ({@code disabledAt} + 30 days), null while the mode is on.
 */
public record MemberDebugStateDto(boolean active, boolean revealSecrets, Instant enabledAt, UUID enabledBy,
                                  Instant disabledAt, Instant purgeAt, long exchangeCount) {
}
