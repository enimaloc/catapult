package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;

public record AdminWhitelistRedemptionDto(String inviteeTwitchId, Instant redeemedAt) {}
