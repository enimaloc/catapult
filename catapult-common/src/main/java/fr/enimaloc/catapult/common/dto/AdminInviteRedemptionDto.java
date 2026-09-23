package fr.enimaloc.catapult.common.dto;

import java.time.Instant;

public record AdminInviteRedemptionDto(String inviteeTwitchId, Instant redeemedAt) {}
