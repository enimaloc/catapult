package fr.enimaloc.catapult.ws.event;


/**
 * Published whenever a channel's status, bindings, or connections change — decouples state
 * mutators (MockApiService, MockAdminController, eventually the real backend) from whoever
 * pushes the change out to browsers (today, ChannelEventStreamController's SSE listener).
 */
public interface ChannelUpdatedEvent {
    String username();
}
