package fr.enimaloc.catapult.common.dto;

import java.util.List;
import java.util.Set;

public record ChannelPageData(
        ChannelUserDto channelUser,
        String channelUsername,
        boolean isOwner,
        boolean isLive,
        boolean botEnabled,
        GameDto currentGame,
        PagedBindings bindings,
        List<CclDto> availableCcls,
        Set<String> blockedCcls,
        List<TwDto> availableTws,
        Set<String> blockedTws,
        String filterStatus,
        String filterSource,
        boolean hasSteamProvider,
        boolean hasSteam,
        boolean hasSteamPersonalToken,
        boolean steamTokenShared,
        boolean steamProfilePrivate,
        boolean steamRateLimited,
        boolean steamOfflineMode,
        long steamProfileCacheTtlMinutes,
        boolean hasXboxProvider,
        boolean hasXbox,
        String exampleUuid
) {}
