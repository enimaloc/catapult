package fr.enimaloc.catapult.event;

/**
 * Published whenever a channel's status, bindings, or connections change — decouples state
 * mutators (MockApiService, MockAdminController, eventually the real backend) from whoever
 * pushes the change out to browsers (ChannelEventStreamController's SSE streams).
 */
public interface ChannelUpdatedEvent {
    String username();
}
