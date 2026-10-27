package fr.enimaloc.catapult.event.binding;

import fr.enimaloc.catapult.common.dto.binding.CustomBindingDto;
import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

import java.util.List;

/**
 * The channel's custom bindings were created, changed or deleted: all of them now, oldest first, and the active one
 * ({@code activeId}, null when none is). Without {@code bindings} (an older catapult-api), the dashboard reloads
 * their list. {@code ts}: when catapult-api published it (its envelope's, epoch millis), for the dashboard to drop
 * one that arrives after a newer one; null when unknown (a catch-up's state, mock mode), never dropped.
 */
public record CustomBindingsUpdatedEvent(String username, List<CustomBindingDto> bindings, String activeId, Long ts)
        implements ChannelUpdatedEvent {

    /** A change that carries nothing but the channel: its dashboard reloads the list. */
    public CustomBindingsUpdatedEvent(String username) {
        this(username, null, null, null);
    }

    /** The list as it is, at no known time. */
    public CustomBindingsUpdatedEvent(String username, List<CustomBindingDto> bindings, String activeId) {
        this(username, bindings, activeId, null);
    }
}
