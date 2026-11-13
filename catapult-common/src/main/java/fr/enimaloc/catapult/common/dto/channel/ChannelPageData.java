package fr.enimaloc.catapult.common.dto.channel;

import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;

import java.util.List;
import java.util.Map;
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
        /** The owner's Battle.net link; null for anyone else, or without Battle.net for the channel. */
        BattleNetData battleNet,
        ObsData obs,
        TwitchatData twitchat,
        /** The owner's custom bindings; null for anyone else, or while the experiment is closed to the channel. */
        CustomBindingsStateDto customBindings,
        /**
         * By Twitch category id, the drops campaign running for the channel that ends the soonest, for the categories
         * the page shows (its bindings', its custom bindings'); null while the drops experiment is closed to it.
         */
        Map<String, DropDto> drops
) {
    /** A page without the Battle.net link. */
    public ChannelPageData(ChannelUserDto channelUser, String channelUsername, boolean isOwner, boolean isLive,
                           boolean botEnabled, GameDto currentGame, PagedBindings bindings, List<CclDto> availableCcls,
                           Set<String> blockedCcls, List<TwDto> availableTws, Set<String> blockedTws,
                           String filterStatus, String filterSource, SteamData steam, XboxData xbox,
                           MinecraftData minecraft, ObsData obs, TwitchatData twitchat,
                           CustomBindingsStateDto customBindings, Map<String, DropDto> drops) {
        this(channelUser, channelUsername, isOwner, isLive, botEnabled, currentGame, bindings, availableCcls,
                blockedCcls, availableTws, blockedTws, filterStatus, filterSource, steam, xbox, minecraft, null, obs,
                twitchat, customBindings, drops);
    }

    /** A page without the drops nor the Battle.net link. */
    public ChannelPageData(ChannelUserDto channelUser, String channelUsername, boolean isOwner, boolean isLive,
                           boolean botEnabled, GameDto currentGame, PagedBindings bindings, List<CclDto> availableCcls,
                           Set<String> blockedCcls, List<TwDto> availableTws, Set<String> blockedTws,
                           String filterStatus, String filterSource, SteamData steam, XboxData xbox,
                           MinecraftData minecraft, ObsData obs, TwitchatData twitchat,
                           CustomBindingsStateDto customBindings) {
        this(channelUser, channelUsername, isOwner, isLive, botEnabled, currentGame, bindings, availableCcls,
                blockedCcls, availableTws, blockedTws, filterStatus, filterSource, steam, xbox, minecraft, null, obs,
                twitchat, customBindings, null);
    }
}
