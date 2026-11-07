package fr.enimaloc.catapult.event.binding;

import com.fasterxml.jackson.annotation.JsonInclude;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingScheduleDto;
import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The channel's custom bindings were created, changed or deleted: all of them now, oldest first, and the active one
 * ({@code activeId}, null when none is). Without {@code bindings} (an older catapult-api), the dashboard reloads
 * their list. {@code ts}: when catapult-api published it (its envelope's, epoch millis), for the dashboard to drop
 * one that arrives after a newer one; null when unknown (a catch-up's state, mock mode), never dropped.
 * {@code schedules}: each binding's schedule slots by id, when the event carries them (null: the dashboard keeps the
 * ones it shows).
 */
public record CustomBindingsUpdatedEvent(String username, List<CustomBindingDto> bindings, String activeId, Long ts,
                                         @JsonInclude(JsonInclude.Include.NON_NULL)
                                         Map<UUID, List<CustomBindingScheduleDto>> schedules)
        implements ChannelUpdatedEvent {

    /** A change that carries nothing but the channel: its dashboard reloads the list. */
    public CustomBindingsUpdatedEvent(String username) {
        this(username, null, null, null, null);
    }

    /** The list as it is, at no known time. */
    public CustomBindingsUpdatedEvent(String username, List<CustomBindingDto> bindings, String activeId) {
        this(username, bindings, activeId, null, null);
    }

    /** The list without the schedules. */
    public CustomBindingsUpdatedEvent(String username, List<CustomBindingDto> bindings, String activeId, Long ts) {
        this(username, bindings, activeId, ts, null);
    }
}
