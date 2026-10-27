package fr.enimaloc.catapult.common.dto.binding;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A streamer's custom bindings, oldest first, and the active one ({@code activeId}/{@code activatedAt} null when
 * none is).
 */
public record CustomBindingsStateDto(
        boolean enabled,
        List<CustomBindingDto> bindings,
        UUID activeId,
        Instant activatedAt
) {}
