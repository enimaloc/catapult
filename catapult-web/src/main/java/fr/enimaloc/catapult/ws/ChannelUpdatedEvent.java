package fr.enimaloc.catapult.ws;

/**
 * Published whenever a channel's status, bindings, or connections change — decouples state
 * mutators (MockApiService, MockAdminController, eventually the real backend) from
 * {@link ChannelWebSocketHandler}, which only needs to listen for it.
 *
 * @param username the channel this change belongs to
 * @param scope    "status", "bindings", or "connections" — informational for now, every scope
 *                 triggers the same client-side refresh
 */
public record ChannelUpdatedEvent(String username, String scope) {
}
