package fr.enimaloc.catapult.common.dto;

import java.time.Instant;

public record InviteRedemptionDto(String inviteeTwitchId, Instant redeemedAt) {}
