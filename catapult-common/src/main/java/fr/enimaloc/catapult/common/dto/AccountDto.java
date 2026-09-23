package fr.enimaloc.catapult.common.dto;

import java.util.UUID;

public record AccountDto(UUID id, String label, String minecraftUsername, int fillOrder,
                         boolean friendLimitReached, boolean enabled, int linkCount) {}
