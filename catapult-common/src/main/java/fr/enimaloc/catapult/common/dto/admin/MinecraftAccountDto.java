package fr.enimaloc.catapult.common.dto.admin;

import java.util.UUID;

/**
 * A Minecraft service account as the admin Keys tab lists it: catapult-api's {@code AccountDto}
 * ({@code GET /api/admin/minecraft-accounts}), read by its JSON field names.
 */
public record MinecraftAccountDto(UUID id, String label, String minecraftUsername, int fillOrder,
                                  long linkCount, boolean enabled, boolean friendLimitReached) {}
