package fr.enimaloc.catapult.ws.event;

import lombok.Data;

import java.util.Objects;

/**
 * Published whenever a channel's status, bindings, or connections change — decouples state
 * mutators (MockApiService, MockAdminController, eventually the real backend) from whoever
 * pushes the change out to browsers (today, ChannelEventStreamController's SSE listener).
 */
@Data
public class ChannelUpdatedEvent {
    private final String username;
}
