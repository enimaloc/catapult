package fr.enimaloc.catapult.common.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminInviteRow(UUID id, UUID ownerId, String ownerUsername, String code,
                        Integer maxUses, int useCount, Boolean canReinvite,
                        Instant createdAt, Instant regeneratedAt,
                        List<AdminInviteRedemptionDto> redemptions) {}
