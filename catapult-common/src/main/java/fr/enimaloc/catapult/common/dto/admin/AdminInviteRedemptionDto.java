package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;

public record AdminInviteRedemptionDto(String inviteeTwitchId, Instant redeemedAt) {}
