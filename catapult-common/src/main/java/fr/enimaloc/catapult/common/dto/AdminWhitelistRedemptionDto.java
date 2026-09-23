package fr.enimaloc.catapult.common.dto;

import java.time.Instant;

public record AdminWhitelistRedemptionDto(String inviteeTwitchId, Instant redeemedAt) {}
