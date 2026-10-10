package fr.enimaloc.catapult.common.dto.chatcommand;

import java.time.Instant;

public record BotModStatusDto(boolean modded, Instant checkedAt) {}
