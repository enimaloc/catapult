package fr.enimaloc.catapult.common.dto.channel;

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
        List<TwDto> availableTws,
        // On an outgoing raid, how recent (minutes) a game is put back as the category; null: off
        Integer raidRestoreGameMinutes,
        // Whether the channel is offered that setting at all (experiment raid.restore-game)
        boolean raidRestoreGameAvailable
) {}
