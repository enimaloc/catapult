package fr.enimaloc.catapult.common.dto;

import java.time.Instant;

public record BotModStatusDto(boolean modded, Instant checkedAt) {}
