package fr.enimaloc.catapult.common.dto.channel;

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
        SteamData steam,
        XboxData xbox,
        MinecraftData minecraft,
        ObsData obs,
        String exampleUuid
) {}
