package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WhitelistInviteRow(UUID id, UUID ownerId, String ownerUsername, String code,
                        Integer maxUses, int useCount, Boolean canReinvite,
                        Instant createdAt, List<AdminWhitelistRedemptionDto> redemptions) {}
