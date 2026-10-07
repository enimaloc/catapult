package fr.enimaloc.catapult.common.dto;

public record SteamData(
        boolean connected,
        boolean hasPersonalToken,
        boolean tokenShared,
        boolean profilePrivate,
        boolean rateLimited,
        boolean offlineMode,
        long profileCacheTtlMinutes
) {
}
