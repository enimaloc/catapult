package fr.enimaloc.catapult.simulation;

import java.util.List;

import static fr.enimaloc.catapult.simulation.CallKey.Scope.ACCOUNT;
import static fr.enimaloc.catapult.simulation.CallKey.Scope.GLOBAL;

/** The closed list of every external call and incoming event the simulation can stand in for. */
public final class SimulatedCalls {

    private SimulatedCalls() {}

    private static final String HELIX = "twitch.helix";
    private static final String CHAT = "twitch.chat";
    private static final String EVENTSUB = "twitch.eventsub";
    private static final String TWITCH_APP = "twitch.app";

    public static final CallKey TWITCH_HELIX_UPDATE_CHANNEL = new CallKey(HELIX, "update-channel", ACCOUNT);
    public static final CallKey TWITCH_HELIX_SET_CATEGORY = new CallKey(HELIX, "set-category", ACCOUNT);
    public static final CallKey TWITCH_HELIX_RESET_TO_DEFAULT = new CallKey(HELIX, "reset-to-default", ACCOUNT);
    public static final CallKey TWITCH_HELIX_FIND_CATEGORY = new CallKey(HELIX, "find-category", ACCOUNT);
    public static final CallKey TWITCH_HELIX_MODERATED_CHANNELS = new CallKey(HELIX, "moderated-channels", ACCOUNT);
    public static final CallKey TWITCH_HELIX_USER_EXTENSIONS = new CallKey(HELIX, "user-extensions", ACCOUNT);
    public static final CallKey TWITCH_HELIX_ACTIVE_EXTENSIONS = new CallKey(HELIX, "active-extensions", ACCOUNT);
    public static final CallKey TWITCH_HELIX_SET_EXTENSIONS = new CallKey(HELIX, "set-extensions", ACCOUNT);
    public static final CallKey TWITCH_HELIX_REFRESH_TOKEN = new CallKey(HELIX, "refresh-token", ACCOUNT);
    /** Whether the system bot moderates the account's channel: asked on the streamer's channel. */
    public static final CallKey TWITCH_HELIX_BOT_MOD_STATUS = new CallKey(HELIX, "bot-mod-status", ACCOUNT);

    public static final CallKey TWITCH_CHAT_CONNECT = new CallKey(CHAT, "connect", ACCOUNT);
    public static final CallKey TWITCH_CHAT_SEND_MESSAGE = new CallKey(CHAT, "send-message", ACCOUNT);
    public static final CallKey TWITCH_CHAT_TIMEOUT = new CallKey(CHAT, "timeout", ACCOUNT);
    public static final CallKey TWITCH_CHAT_BAN = new CallKey(CHAT, "ban", ACCOUNT);
    public static final CallKey TWITCH_CHAT_UNBAN = new CallKey(CHAT, "unban", ACCOUNT);
    public static final CallKey TWITCH_CHAT_STREAM_INFO = new CallKey(CHAT, "stream-info", ACCOUNT);
    public static final CallKey TWITCH_CHAT_USER_PROFILE = new CallKey(CHAT, "user-profile", ACCOUNT);
    public static final CallKey TWITCH_CHAT_FOLLOWED_AT = new CallKey(CHAT, "followed-at", ACCOUNT);
    public static final CallKey TWITCH_CHAT_SHOUTOUT = new CallKey(CHAT, "shoutout", ACCOUNT);

    /** Incoming events: a mode of REAL opens the real EventSub subscription. */
    public static final CallKey TWITCH_EVENTSUB_STREAM_ONLINE = new CallKey(EVENTSUB, "stream-online", ACCOUNT);
    public static final CallKey TWITCH_EVENTSUB_STREAM_OFFLINE = new CallKey(EVENTSUB, "stream-offline", ACCOUNT);
    public static final CallKey TWITCH_EVENTSUB_CHANNEL_UPDATE = new CallKey(EVENTSUB, "channel-update", ACCOUNT);
    public static final CallKey TWITCH_EVENTSUB_CHANNEL_RAID = new CallKey(EVENTSUB, "channel-raid", ACCOUNT);

    public static final CallKey TWITCH_APP_CATEGORY_SEARCH = new CallKey(TWITCH_APP, "category-search", GLOBAL);
    public static final CallKey TWITCH_APP_APP_TOKEN = new CallKey(TWITCH_APP, "app-token", GLOBAL);
    /** The system bot account's token refresh: it belongs to no streamer. */
    public static final CallKey TWITCH_APP_BOT_REFRESH = new CallKey(TWITCH_APP, "bot-refresh", GLOBAL);

    public static final CallKey STEAM_PRESENCE = new CallKey("steam", "presence", ACCOUNT);
    public static final CallKey STEAM_PROFILE_STATUS = new CallKey("steam", "profile-status", ACCOUNT);
    public static final CallKey STEAM_OWNED_GAMES = new CallKey("steam", "owned-games", ACCOUNT);
    public static final CallKey STEAM_STORE_APP_DETAILS = new CallKey("steam.store", "app-details", GLOBAL);
    public static final CallKey STEAM_STORE_CCLS = new CallKey("steam.store", "ccls", GLOBAL);
    public static final CallKey STEAM_STORE_TW_SIGNALS = new CallKey("steam.store", "tw-signals", GLOBAL);
    public static final CallKey STEAM_STORE_LIBRARY = new CallKey("steam.store", "library", GLOBAL);

    public static final CallKey XBOX_PRESENCE = new CallKey("xbox", "presence", ACCOUNT);
    public static final CallKey XBOX_STORE_PRODUCT = new CallKey("xbox.store", "product", GLOBAL);

    public static final CallKey MINECRAFT_PRESENCE = new CallKey("minecraft", "presence", ACCOUNT);
    /** The account's enrolment and unenrolment: the profile lookup and the service account's friend add/remove. */
    public static final CallKey MINECRAFT_FRIENDS = new CallKey("minecraft", "friends", ACCOUNT);
    public static final CallKey BATTLENET_PRESENCE = new CallKey("battlenet", "presence", ACCOUNT);

    public static final CallKey IGDB_SEARCH = new CallKey("igdb", "search", GLOBAL);
    public static final CallKey IGDB_GAME = new CallKey("igdb", "game", GLOBAL);
    public static final CallKey DTDD_SEARCH = new CallKey("dtdd", "search", GLOBAL);
    public static final CallKey DTDD_TOPICS = new CallKey("dtdd", "topics", GLOBAL);
    public static final CallKey GITLAB_FEEDBACK = new CallKey("gitlab", "feedback", GLOBAL);

    private static final List<CallKey> ALL = List.of(
            TWITCH_HELIX_UPDATE_CHANNEL, TWITCH_HELIX_SET_CATEGORY, TWITCH_HELIX_RESET_TO_DEFAULT,
            TWITCH_HELIX_FIND_CATEGORY, TWITCH_HELIX_MODERATED_CHANNELS,
            TWITCH_HELIX_USER_EXTENSIONS, TWITCH_HELIX_ACTIVE_EXTENSIONS, TWITCH_HELIX_SET_EXTENSIONS,
            TWITCH_HELIX_REFRESH_TOKEN, TWITCH_HELIX_BOT_MOD_STATUS,
            TWITCH_CHAT_CONNECT, TWITCH_CHAT_SEND_MESSAGE, TWITCH_CHAT_TIMEOUT, TWITCH_CHAT_BAN, TWITCH_CHAT_UNBAN,
            TWITCH_CHAT_STREAM_INFO, TWITCH_CHAT_USER_PROFILE, TWITCH_CHAT_FOLLOWED_AT, TWITCH_CHAT_SHOUTOUT,
            TWITCH_EVENTSUB_STREAM_ONLINE, TWITCH_EVENTSUB_STREAM_OFFLINE, TWITCH_EVENTSUB_CHANNEL_UPDATE,
            TWITCH_EVENTSUB_CHANNEL_RAID,
            TWITCH_APP_CATEGORY_SEARCH, TWITCH_APP_APP_TOKEN, TWITCH_APP_BOT_REFRESH,
            STEAM_PRESENCE, STEAM_PROFILE_STATUS, STEAM_OWNED_GAMES,
            STEAM_STORE_APP_DETAILS, STEAM_STORE_CCLS, STEAM_STORE_TW_SIGNALS, STEAM_STORE_LIBRARY,
            XBOX_PRESENCE, XBOX_STORE_PRODUCT, MINECRAFT_PRESENCE, MINECRAFT_FRIENDS, BATTLENET_PRESENCE,
            IGDB_SEARCH, IGDB_GAME, DTDD_SEARCH, DTDD_TOPICS, GITLAB_FEEDBACK);

    public static List<CallKey> all() {
        return ALL;
    }

    /**
     * Whether {@code key} is a presence, asked at every detection cycle: the journal only keeps its answers that
     * change, or the polls would push everything else out of it.
     */
    public static boolean polled(CallKey key) {
        return "presence".equals(key.call());
    }
}
