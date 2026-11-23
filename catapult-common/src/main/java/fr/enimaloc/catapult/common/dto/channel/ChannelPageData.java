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
        Map<String, DropDto> drops,
        /** The owner's Ankama link; null for anyone else, or without Ankama for the channel. */
        AnkamaData ankama,
        /** The owner's Epic link; null for anyone else, or without Epic for the channel. */
        EpicData epic,
        /** The owner's speedrun.com account; null for anyone else. */
        SpeedrunData speedrun,
        /** The owner's RetroAchievements account; null for anyone else. Names and flags only, never the key. */
        RetroAchievementsData retroAchievements,
        /** The owner's Blizzard settings; null for anyone else, or without Battle.net on this instance. Names only. */
        BlizzardStatsData blizzardStats,
        /** The owner's Bungie.net link; null for anyone else, or when Bungie is not set up and nothing is linked. */
        BungieAccountData bungie,
        /** The owner's Riot settings; null for anyone else. The Riot ID and platform only. */
        RiotData riot,
        /** The owner's FACEIT settings; null for anyone else. The nickname only. */
        FaceitData faceit,
        /** The owner's chess accounts (Chess.com, Lichess); null for anyone else. Names only. */
        ChessData chess,
        /** The owner's osu! link; null for anyone else, or when osu! is not set up and nothing is linked. */
        OsuAccountData osu,
        /** The owner's Hypixel settings; null for anyone else. The Minecraft name and UUID only. */
        HypixelData hypixel,
        /** The owner's Supercell settings; null for anyone else. The player tags and names only. */
        SupercellData supercell
) {
    /** A page without the Supercell settings. */
    public ChannelPageData(ChannelUserDto channelUser, String channelUsername, boolean isOwner, boolean isLive,
                           boolean botEnabled, GameDto currentGame, PagedBindings bindings, List<CclDto> availableCcls,
                           Set<String> blockedCcls, List<TwDto> availableTws, Set<String> blockedTws,
                           String filterStatus, String filterSource, SteamData steam, XboxData xbox,
                           MinecraftData minecraft, BattleNetData battleNet, ObsData obs, TwitchatData twitchat,
                           CustomBindingsStateDto customBindings, Map<String, DropDto> drops, AnkamaData ankama,
                           EpicData epic, SpeedrunData speedrun, RetroAchievementsData retroAchievements,
                           BlizzardStatsData blizzardStats, BungieAccountData bungie, RiotData riot,
                           FaceitData faceit, ChessData chess, OsuAccountData osu, HypixelData hypixel) {
        this(channelUser, channelUsername, isOwner, isLive, botEnabled, currentGame, bindings, availableCcls,
                blockedCcls, availableTws, blockedTws, filterStatus, filterSource, steam, xbox, minecraft, battleNet,
                obs, twitchat, customBindings, drops, ankama, epic, speedrun, retroAchievements, blizzardStats, bungie,
                riot, faceit, chess, osu, hypixel, null);
    }

    /** A page without the Hypixel settings. */
    public ChannelPageData(ChannelUserDto channelUser, String channelUsername, boolean isOwner, boolean isLive,
                           boolean botEnabled, GameDto currentGame, PagedBindings bindings, List<CclDto> availableCcls,
                           Set<String> blockedCcls, List<TwDto> availableTws, Set<String> blockedTws,
                           String filterStatus, String filterSource, SteamData steam, XboxData xbox,
                           MinecraftData minecraft, BattleNetData battleNet, ObsData obs, TwitchatData twitchat,
                           CustomBindingsStateDto customBindings, Map<String, DropDto> drops, AnkamaData ankama,
                           EpicData epic, SpeedrunData speedrun, RetroAchievementsData retroAchievements,
                           BlizzardStatsData blizzardStats, BungieAccountData bungie, RiotData riot,
                           FaceitData faceit, ChessData chess, OsuAccountData osu) {
        this(channelUser, channelUsername, isOwner, isLive, botEnabled, currentGame, bindings, availableCcls,
                blockedCcls, availableTws, blockedTws, filterStatus, filterSource, steam, xbox, minecraft, battleNet,
                obs, twitchat, customBindings, drops, ankama, epic, speedrun, retroAchievements, blizzardStats, bungie,
                riot, faceit, chess, osu, null);
    }

    /** A page without the osu! link. */
    public ChannelPageData(ChannelUserDto channelUser, String channelUsername, boolean isOwner, boolean isLive,
                           boolean botEnabled, GameDto currentGame, PagedBindings bindings, List<CclDto> availableCcls,
                           Set<String> blockedCcls, List<TwDto> availableTws, Set<String> blockedTws,
                           String filterStatus, String filterSource, SteamData steam, XboxData xbox,
                           MinecraftData minecraft, BattleNetData battleNet, ObsData obs, TwitchatData twitchat,
                           CustomBindingsStateDto customBindings, Map<String, DropDto> drops, AnkamaData ankama,
                           EpicData epic, SpeedrunData speedrun, RetroAchievementsData retroAchievements,
                           BlizzardStatsData blizzardStats, BungieAccountData bungie, RiotData riot,
                           FaceitData faceit, ChessData chess) {
        this(channelUser, channelUsername, isOwner, isLive, botEnabled, currentGame, bindings, availableCcls,
                blockedCcls, availableTws, blockedTws, filterStatus, filterSource, steam, xbox, minecraft, battleNet,
                obs, twitchat, customBindings, drops, ankama, epic, speedrun, retroAchievements, blizzardStats, bungie,
                riot, faceit, chess, null);
    }

    /** A page without the chess accounts. */
    public ChannelPageData(ChannelUserDto channelUser, String channelUsername, boolean isOwner, boolean isLive,
                           boolean botEnabled, GameDto currentGame, PagedBindings bindings, List<CclDto> availableCcls,
                           Set<String> blockedCcls, List<TwDto> availableTws, Set<String> blockedTws,
                           String filterStatus, String filterSource, SteamData steam, XboxData xbox,
                           MinecraftData minecraft, BattleNetData battleNet, ObsData obs, TwitchatData twitchat,
                           CustomBindingsStateDto customBindings, Map<String, DropDto> drops, AnkamaData ankama,
                           EpicData epic, SpeedrunData speedrun, RetroAchievementsData retroAchievements,
                           BlizzardStatsData blizzardStats, BungieAccountData bungie, RiotData riot,
                           FaceitData faceit) {
        this(channelUser, channelUsername, isOwner, isLive, botEnabled, currentGame, bindings, availableCcls,
                blockedCcls, availableTws, blockedTws, filterStatus, filterSource, steam, xbox, minecraft, battleNet,
                obs, twitchat, customBindings, drops, ankama, epic, speedrun, retroAchievements, blizzardStats, bungie,
                riot, faceit, null);
    }

    /** A page without the FACEIT settings. */
    public ChannelPageData(ChannelUserDto channelUser, String channelUsername, boolean isOwner, boolean isLive,
                           boolean botEnabled, GameDto currentGame, PagedBindings bindings, List<CclDto> availableCcls,
                           Set<String> blockedCcls, List<TwDto> availableTws, Set<String> blockedTws,
                           String filterStatus, String filterSource, SteamData steam, XboxData xbox,
                           MinecraftData minecraft, BattleNetData battleNet, ObsData obs, TwitchatData twitchat,
                           CustomBindingsStateDto customBindings, Map<String, DropDto> drops, AnkamaData ankama,
                           EpicData epic, SpeedrunData speedrun, RetroAchievementsData retroAchievements,
                           BlizzardStatsData blizzardStats, BungieAccountData bungie, RiotData riot) {
        this(channelUser, channelUsername, isOwner, isLive, botEnabled, currentGame, bindings, availableCcls,
                blockedCcls, availableTws, blockedTws, filterStatus, filterSource, steam, xbox, minecraft, battleNet,
                obs, twitchat, customBindings, drops, ankama, epic, speedrun, retroAchievements, blizzardStats, bungie,
                riot, null, null);
    }

    /** A page without the Riot settings. */
    public ChannelPageData(ChannelUserDto channelUser, String channelUsername, boolean isOwner, boolean isLive,
                           boolean botEnabled, GameDto currentGame, PagedBindings bindings, List<CclDto> availableCcls,
                           Set<String> blockedCcls, List<TwDto> availableTws, Set<String> blockedTws,
                           String filterStatus, String filterSource, SteamData steam, XboxData xbox,
                           MinecraftData minecraft, BattleNetData battleNet, ObsData obs, TwitchatData twitchat,
                           CustomBindingsStateDto customBindings, Map<String, DropDto> drops, AnkamaData ankama,
                           EpicData epic, SpeedrunData speedrun, RetroAchievementsData retroAchievements,
                           BlizzardStatsData blizzardStats, BungieAccountData bungie) {
        this(channelUser, channelUsername, isOwner, isLive, botEnabled, currentGame, bindings, availableCcls,
                blockedCcls, availableTws, blockedTws, filterStatus, filterSource, steam, xbox, minecraft, battleNet,
                obs, twitchat, customBindings, drops, ankama, epic, speedrun, retroAchievements, blizzardStats, bungie,
                null, null);
    }

    /** A page without the Bungie.net link nor the Riot settings. */
    public ChannelPageData(ChannelUserDto channelUser, String channelUsername, boolean isOwner, boolean isLive,
                           boolean botEnabled, GameDto currentGame, PagedBindings bindings, List<CclDto> availableCcls,
                           Set<String> blockedCcls, List<TwDto> availableTws, Set<String> blockedTws,
                           String filterStatus, String filterSource, SteamData steam, XboxData xbox,
                           MinecraftData minecraft, BattleNetData battleNet, ObsData obs, TwitchatData twitchat,
                           CustomBindingsStateDto customBindings, Map<String, DropDto> drops, AnkamaData ankama,
                           EpicData epic, SpeedrunData speedrun, RetroAchievementsData retroAchievements,
                           BlizzardStatsData blizzardStats) {
        this(channelUser, channelUsername, isOwner, isLive, botEnabled, currentGame, bindings, availableCcls,
                blockedCcls, availableTws, blockedTws, filterStatus, filterSource, steam, xbox, minecraft, battleNet,
                obs, twitchat, customBindings, drops, ankama, epic, speedrun, retroAchievements, blizzardStats, null,
                null, null);
    }

    /** A page without the Blizzard settings. */
    public ChannelPageData(ChannelUserDto channelUser, String channelUsername, boolean isOwner, boolean isLive,
                           boolean botEnabled, GameDto currentGame, PagedBindings bindings, List<CclDto> availableCcls,
                           Set<String> blockedCcls, List<TwDto> availableTws, Set<String> blockedTws,
                           String filterStatus, String filterSource, SteamData steam, XboxData xbox,
                           MinecraftData minecraft, BattleNetData battleNet, ObsData obs, TwitchatData twitchat,
                           CustomBindingsStateDto customBindings, Map<String, DropDto> drops, AnkamaData ankama,
                           EpicData epic, SpeedrunData speedrun, RetroAchievementsData retroAchievements) {
        this(channelUser, channelUsername, isOwner, isLive, botEnabled, currentGame, bindings, availableCcls,
                blockedCcls, availableTws, blockedTws, filterStatus, filterSource, steam, xbox, minecraft, battleNet,
                obs, twitchat, customBindings, drops, ankama, epic, speedrun, retroAchievements, null, null, null);
    }

    /** A page without the RetroAchievements account. */
    public ChannelPageData(ChannelUserDto channelUser, String channelUsername, boolean isOwner, boolean isLive,
                           boolean botEnabled, GameDto currentGame, PagedBindings bindings, List<CclDto> availableCcls,
                           Set<String> blockedCcls, List<TwDto> availableTws, Set<String> blockedTws,
                           String filterStatus, String filterSource, SteamData steam, XboxData xbox,
                           MinecraftData minecraft, BattleNetData battleNet, ObsData obs, TwitchatData twitchat,
                           CustomBindingsStateDto customBindings, Map<String, DropDto> drops, AnkamaData ankama,
                           EpicData epic, SpeedrunData speedrun) {
        this(channelUser, channelUsername, isOwner, isLive, botEnabled, currentGame, bindings, availableCcls,
                blockedCcls, availableTws, blockedTws, filterStatus, filterSource, steam, xbox, minecraft, battleNet,
                obs, twitchat, customBindings, drops, ankama, epic, speedrun, null, null, null);
    }

    /** A page without the speedrun.com account. */
    public ChannelPageData(ChannelUserDto channelUser, String channelUsername, boolean isOwner, boolean isLive,
                           boolean botEnabled, GameDto currentGame, PagedBindings bindings, List<CclDto> availableCcls,
                           Set<String> blockedCcls, List<TwDto> availableTws, Set<String> blockedTws,
                           String filterStatus, String filterSource, SteamData steam, XboxData xbox,
                           MinecraftData minecraft, BattleNetData battleNet, ObsData obs, TwitchatData twitchat,
                           CustomBindingsStateDto customBindings, Map<String, DropDto> drops, AnkamaData ankama,
                           EpicData epic) {
        this(channelUser, channelUsername, isOwner, isLive, botEnabled, currentGame, bindings, availableCcls,
                blockedCcls, availableTws, blockedTws, filterStatus, filterSource, steam, xbox, minecraft, battleNet,
                obs, twitchat, customBindings, drops, ankama, epic, null, null, null);
    }

    /** A page without the Epic link. */
    public ChannelPageData(ChannelUserDto channelUser, String channelUsername, boolean isOwner, boolean isLive,
                           boolean botEnabled, GameDto currentGame, PagedBindings bindings, List<CclDto> availableCcls,
                           Set<String> blockedCcls, List<TwDto> availableTws, Set<String> blockedTws,
                           String filterStatus, String filterSource, SteamData steam, XboxData xbox,
                           MinecraftData minecraft, BattleNetData battleNet, ObsData obs, TwitchatData twitchat,
                           CustomBindingsStateDto customBindings, Map<String, DropDto> drops, AnkamaData ankama) {
        this(channelUser, channelUsername, isOwner, isLive, botEnabled, currentGame, bindings, availableCcls,
                blockedCcls, availableTws, blockedTws, filterStatus, filterSource, steam, xbox, minecraft, battleNet,
                obs, twitchat, customBindings, drops, ankama, null, null, null, null);
    }

    /** A page without the Ankama nor the Epic link. */
    public ChannelPageData(ChannelUserDto channelUser, String channelUsername, boolean isOwner, boolean isLive,
                           boolean botEnabled, GameDto currentGame, PagedBindings bindings, List<CclDto> availableCcls,
                           Set<String> blockedCcls, List<TwDto> availableTws, Set<String> blockedTws,
                           String filterStatus, String filterSource, SteamData steam, XboxData xbox,
                           MinecraftData minecraft, BattleNetData battleNet, ObsData obs, TwitchatData twitchat,
                           CustomBindingsStateDto customBindings, Map<String, DropDto> drops) {
        this(channelUser, channelUsername, isOwner, isLive, botEnabled, currentGame, bindings, availableCcls,
                blockedCcls, availableTws, blockedTws, filterStatus, filterSource, steam, xbox, minecraft, battleNet,
                obs, twitchat, customBindings, drops, null, null, null, null, null);
    }

    /** A page without the Battle.net link. */
    public ChannelPageData(ChannelUserDto channelUser, String channelUsername, boolean isOwner, boolean isLive,
                           boolean botEnabled, GameDto currentGame, PagedBindings bindings, List<CclDto> availableCcls,
                           Set<String> blockedCcls, List<TwDto> availableTws, Set<String> blockedTws,
                           String filterStatus, String filterSource, SteamData steam, XboxData xbox,
                           MinecraftData minecraft, ObsData obs, TwitchatData twitchat,
                           CustomBindingsStateDto customBindings, Map<String, DropDto> drops) {
        this(channelUser, channelUsername, isOwner, isLive, botEnabled, currentGame, bindings, availableCcls,
                blockedCcls, availableTws, blockedTws, filterStatus, filterSource, steam, xbox, minecraft, null, obs,
                twitchat, customBindings, drops, null, null, null, null, null);
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
                twitchat, customBindings, null, null, null, null, null, null);
    }
}
