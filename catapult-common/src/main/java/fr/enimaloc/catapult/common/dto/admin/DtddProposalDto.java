package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;
import java.util.UUID;

/**
 * A member's proposal to map an IGDB game to a DoesTheDogDie id, as catapult-api lists it for the
 * admin review. {@code proposer} is null when catapult-api does not expose it; {@code status} is
 * PENDING, APPROVED or REJECTED.
 */
public record DtddProposalDto(UUID id, Instant createdAt, String igdbId, Long proposedDtddId, Proposer proposer,
                              String reason, String status) {
    public record Proposer(String twitchUsername) {}
}
