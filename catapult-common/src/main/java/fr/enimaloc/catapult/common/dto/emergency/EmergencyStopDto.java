package fr.enimaloc.catapult.common.dto.emergency;

import java.time.Instant;
import java.util.UUID;

/**
 * A provider emergency stop. {@code provider} is a provider name or {@code ALL}, {@code scope} {@code GLOBAL} or
 * {@code CHANNEL}, {@code origin} {@code THRESHOLD}, {@code CHANNEL_REPORT} or {@code OWNER}. The channel login is
 * only set for a channel stop; {@code expiresAt} is null for an owner stop; the lift fields once it was lifted.
 */
public record EmergencyStopDto(UUID id, String provider, String scope, String origin, String channelLogin,
                               Instant createdAt, Instant expiresAt, Instant liftedAt, String liftedBy) {}
