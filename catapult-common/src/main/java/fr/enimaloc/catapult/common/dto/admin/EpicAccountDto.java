package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;
import java.util.UUID;

/**
 * An Epic bot account as the admin API shows it: never the access or the refresh token. {@code refreshExpiresAt}:
 * when the bot needs a new login at the latest; {@code refreshExpiresSoon} once that is less than 30 days away.
 */
public record EpicAccountDto(UUID id, String displayName, String authState, String sessionState, int fillOrder,
                             boolean friendLimitReached, boolean enabled, long friendCount, Instant refreshExpiresAt,
                             boolean refreshExpiresSoon) {}
