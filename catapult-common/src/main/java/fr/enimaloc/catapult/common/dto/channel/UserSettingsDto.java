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
        boolean raidRestoreGameAvailable,
        // The Twitch extension layouts of the two fallbacks (empty: they change nothing)
        List<ExtensionSlotDto> noGameExtensions,
        List<ExtensionSlotDto> incompleteFallbackExtensions,
        // Off: Catapult never touches the stream title
        boolean titleFeatureEnabled,
        // The channel-wide title ({game}: the category's name); null: none
        String titleTemplate,
        // Whether the channel is offered the title setting at all (experiment stream.title)
        boolean titleAvailable
) {
    /** Settings without the stream title (off, and not offered). */
    public UserSettingsDto(boolean cclFeatureEnabled, Set<String> blockedCcls, String noGameTwitchGameId,
                           String noGameTwitchGameName, Set<String> noGameCcls, boolean applyDefaultOnStreamStart,
                           boolean applyDefaultOnNoGame, boolean applyDefaultOnStreamEnd,
                           String incompleteFallbackTwitchGameId, String incompleteFallbackTwitchGameName,
                           Set<String> incompleteFallbackCcls, List<CclDto> availableCcls, boolean twFeatureEnabled,
                           Set<String> blockedTws, List<TwDto> availableTws, Integer raidRestoreGameMinutes,
                           boolean raidRestoreGameAvailable, List<ExtensionSlotDto> noGameExtensions,
                           List<ExtensionSlotDto> incompleteFallbackExtensions) {
        this(cclFeatureEnabled, blockedCcls, noGameTwitchGameId, noGameTwitchGameName, noGameCcls,
                applyDefaultOnStreamStart, applyDefaultOnNoGame, applyDefaultOnStreamEnd,
                incompleteFallbackTwitchGameId, incompleteFallbackTwitchGameName, incompleteFallbackCcls,
                availableCcls, twFeatureEnabled, blockedTws, availableTws, raidRestoreGameMinutes,
                raidRestoreGameAvailable, noGameExtensions, incompleteFallbackExtensions, false, null, false);
    }

    /** Settings without extension layouts nor the stream title. */
    public UserSettingsDto(boolean cclFeatureEnabled, Set<String> blockedCcls, String noGameTwitchGameId,
                           String noGameTwitchGameName, Set<String> noGameCcls, boolean applyDefaultOnStreamStart,
                           boolean applyDefaultOnNoGame, boolean applyDefaultOnStreamEnd,
                           String incompleteFallbackTwitchGameId, String incompleteFallbackTwitchGameName,
                           Set<String> incompleteFallbackCcls, List<CclDto> availableCcls, boolean twFeatureEnabled,
                           Set<String> blockedTws, List<TwDto> availableTws, Integer raidRestoreGameMinutes,
                           boolean raidRestoreGameAvailable) {
        this(cclFeatureEnabled, blockedCcls, noGameTwitchGameId, noGameTwitchGameName, noGameCcls,
                applyDefaultOnStreamStart, applyDefaultOnNoGame, applyDefaultOnStreamEnd,
                incompleteFallbackTwitchGameId, incompleteFallbackTwitchGameName, incompleteFallbackCcls,
                availableCcls, twFeatureEnabled, blockedTws, availableTws, raidRestoreGameMinutes,
                raidRestoreGameAvailable, List.of(), List.of());
    }
}
