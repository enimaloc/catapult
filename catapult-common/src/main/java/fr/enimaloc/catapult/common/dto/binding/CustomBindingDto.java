package fr.enimaloc.catapult.common.dto.binding;

import java.util.List;
import java.util.UUID;

/** A streamer's custom binding as the API answers it (CCLs and TWs sorted). */
public record CustomBindingDto(
        UUID id,
        String name,
        String twitchGameId,
        String twitchGameName,
        List<String> ccls,
        List<String> tws,
        boolean uninterruptible
) {}
