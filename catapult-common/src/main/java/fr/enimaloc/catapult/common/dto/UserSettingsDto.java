package fr.enimaloc.catapult.common.dto;

import java.util.List;
import java.util.Set;

public record UserSettingsDto(
        boolean cclFeatureEnabled,
        Set<String> blockedCcls,
        String noGameTwitchGameId,
        String noGameTwitchGameName,
        Set<String> noGameCcls,
        boolean applyDefaultOnStreamStart,
        boolean applyDefaultOnNoGame,
        boolean applyDefaultOnStreamEnd,
        String incompleteFallbackTwitchGameId,
        String incompleteFallbackTwitchGameName,
        Set<String> incompleteFallbackCcls,
        List<CclDto> availableCcls,
        boolean twFeatureEnabled,
        Set<String> blockedTws,
        List<TwDto> availableTws
) {}
