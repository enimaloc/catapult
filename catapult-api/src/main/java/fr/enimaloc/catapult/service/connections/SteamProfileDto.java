package fr.enimaloc.catapult.service.connections;

/**
 * Snapshot of a channel's Steam profile state, carried inside
 * {@code steam.profile.changed} and (when provider=STEAM) inside
 * {@code connection.changed} WS event payloads.
 *
 * <p>The channel-page JS uses these fields to update the connections panel
 * in-place without a server round-trip.</p>
 */
public record SteamProfileDto(
        boolean hasSteam,
        boolean profilePrivate,
        boolean offlineMode,
        boolean rateLimited,
        Long ttlMinutes,
        boolean hasPersonalToken,
        boolean tokenShared
) {}
