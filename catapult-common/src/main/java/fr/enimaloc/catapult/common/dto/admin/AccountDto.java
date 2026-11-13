package fr.enimaloc.catapult.common.dto.admin;

import java.util.UUID;

/** A Minecraft service account; {@code displayName} is its Minecraft username. */
public record AccountDto(UUID id, String displayName, String minecraftUsername, int fillOrder,
                         boolean friendLimitReached, boolean enabled, int linkCount) {}
