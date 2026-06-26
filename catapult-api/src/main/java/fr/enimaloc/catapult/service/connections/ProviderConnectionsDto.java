package fr.enimaloc.catapult.service.connections;

/**
 * Snapshot of a provider connection state, carried in the
 * {@code connection.changed} WS event payload.
 *
 * <p>{@code profile} is non-null only when {@code provider} is {@code "STEAM"}
 * and the Steam account is (still) connected at the time of the event.</p>
 */
public record ProviderConnectionsDto(
        String provider,
        boolean connected,
        SteamProfileDto profile
) {}
