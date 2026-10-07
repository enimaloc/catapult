package fr.enimaloc.catapult.common.dto.channel;

import java.util.Set;

public record BindingDto(
        String id,
        String status,
        String sourceType,
        String sourceName,
        String twitchGameId,
        String twitchGameName,
        boolean ignored,
        boolean cclEnabled,
        Set<String> ccls,
        boolean twEnabled,
        boolean twOverride,
        Set<String> tws
) {}
