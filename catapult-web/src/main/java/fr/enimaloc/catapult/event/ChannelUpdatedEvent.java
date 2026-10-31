package fr.enimaloc.catapult.event;

/**
 * Published whenever a channel's status, bindings, or connections change — decouples where the
 * change comes from (catapult-api's events relayed from Redis by ChannelEventRedisBridge, on every
 * instance; MockApiService and MockAdminController in mock mode) from whoever pushes it out to
 * browsers (ChannelEventStreamController's SSE streams). {@code username} is the channel's login.
 */
public interface ChannelUpdatedEvent {
    String username();
}
