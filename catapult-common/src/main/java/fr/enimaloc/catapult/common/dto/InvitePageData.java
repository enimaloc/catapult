package fr.enimaloc.catapult.common.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InvitePageData(boolean canInvite, UUID inviteId, String code, String inviteUrl,
                             Instant regeneratedAt, List<InviteRedemptionDto> redemptions) {}
