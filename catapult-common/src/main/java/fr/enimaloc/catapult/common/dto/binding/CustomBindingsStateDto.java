package fr.enimaloc.catapult.common.dto.binding;

import com.fasterxml.jackson.annotation.JsonInclude;
import fr.enimaloc.catapult.common.dto.channel.ExtensionSlotDto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A streamer's custom bindings, oldest first, and the active one ({@code activeId}/{@code activatedAt} null when
 * none is). {@code extensions} is each custom binding's Twitch extension layout by id, apart from the bindings so
 * that the public token API's {@link CustomBindingDto} stays as it is.
 */
public record CustomBindingsStateDto(
        boolean enabled,
        List<CustomBindingDto> bindings,
        UUID activeId,
        Instant activatedAt,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<UUID, List<ExtensionSlotDto>> extensions
) {
    public CustomBindingsStateDto {
        if (extensions == null) extensions = Map.of();
    }

    public CustomBindingsStateDto(boolean enabled, List<CustomBindingDto> bindings, UUID activeId,
                                  Instant activatedAt) {
        this(enabled, bindings, activeId, activatedAt, Map.of());
    }
}
