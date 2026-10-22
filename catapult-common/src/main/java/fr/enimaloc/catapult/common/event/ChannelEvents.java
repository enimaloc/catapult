package fr.enimaloc.catapult.common.event;

import fr.enimaloc.catapult.common.dto.channel.BindingDto;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The wire contract of catapult-api's per-channel events: published on {@code catapult:events:channel:{ownerId}}
 * as an envelope {@code {name, data, ts}}, {@code data} being one of the records below, and relayed by every
 * catapult-web instance to the browsers following that channel.
 * <p>
 * Each payload names its channel twice: {@code channelId} (the owner's account id, as in the Redis channel's
 * name) and {@code channelLogin} (the owner's Twitch login, lower case), which is what catapult-web keys its
 * streams by.
 */
public final class ChannelEvents {

    /** The Redis channel prefix, followed by the owner's account id. */
    public static final String REDIS_PREFIX = "catapult:events:channel:";

    public static final String BOT_TOGGLED = "bot.toggled";
    public static final String STREAM_STATE_CHANGED = "stream.state.changed";
    public static final String GAME_DETECTED = "game.detected";
    public static final String GAME_CLEARED = "game.cleared";
    public static final String BINDING_UPSERTED = "binding.upserted";
    public static final String BINDING_DELETED = "binding.deleted";
    public static final String SETTINGS_UPDATED = "settings.updated";
    public static final String STEAM_PROFILE_CHANGED = "steam.profile.changed";
    public static final String CONNECTION_CHANGED = "connection.changed";
    public static final String DTDD_MAPPING_CHANGED = "dtdd.mapping.changed";
    public static final String MINECRAFT_LINK_CHANGED = "minecraft.link.changed";

    /**
     * Not a per-channel event: {@link NotificationDeliveryPending} names many members at once, so it has its
     * own Redis channel (same envelope) instead of one {@code catapult:events:channel:{ownerId}} each.
     */
    public static final String NOTIFICATION_DELIVERY_REDIS_CHANNEL = "catapult:events:notification-delivery";
    public static final String NOTIFICATION_DELIVERY_PENDING = "notification.delivery.pending";

    private ChannelEvents() {
    }

    /** The bot was switched on or off. */
    public record BotToggled(String channelId, String channelLogin, boolean enabled) {}

    /** The channel went live or offline. */
    public record StreamStateChanged(String channelId, String channelLogin, boolean isLive) {}

    /**
     * A game is now detected; {@code bindingId} is the channel's binding for it, null while it has none yet
     * (a {@link BindingUpserted} follows once it is created).
     */
    public record GameDetected(String channelId, String channelLogin, String bindingId,
                               String sourceName, String sourceType) {}

    /** No game is detected any more. */
    public record GameCleared(String channelId, String channelLogin) {}

    /** A binding was created or changed: its whole state. */
    public record BindingUpserted(String channelId, String channelLogin, BindingDto binding) {}

    /** A binding was deleted. */
    public record BindingDeleted(String channelId, String channelLogin, String bindingId) {}

    /** The channel's settings changed: each panel's whole state. */
    public record SettingsUpdated(String channelId, String channelLogin, Settings settings) {}

    /** The channel's Steam profile, as the dashboard reads it, changed. */
    public record SteamProfileChanged(String channelId, String channelLogin, SteamProfile profile) {}

    /** A provider was connected or disconnected; {@code profile} only for a Steam account still connected. */
    public record ConnectionChanged(String channelId, String channelLogin, String provider, boolean connected,
                                    SteamProfile profile) {}

    /** The DoesTheDogDie mapping of the channel's games changed. */
    public record DtddMappingChanged(String channelId, String channelLogin) {}

    /** The channel's Minecraft link changed: its status ({@code NONE} once gone) and player name. */
    public record MinecraftLinkChanged(String channelId, String channelLogin, String status, String minecraftName) {}

    /**
     * New notifications wait to be sent to these members' Twitchat: whichever catapult-web instance holds one
     * of their Twitchat pages or OBS widgets open asks catapult-api to flush them. Published on
     * {@link #NOTIFICATION_DELIVERY_REDIS_CHANNEL}, at most a few hundred ids per message.
     */
    public record NotificationDeliveryPending(List<UUID> userIds) {}

    /** A channel's Steam profile state, as the dashboard shows it. */
    public record SteamProfile(boolean hasSteam, boolean profilePrivate, boolean offlineMode, boolean rateLimited,
                               Long ttlMinutes, boolean hasPersonalToken, boolean tokenShared) {}

    /** A channel's settings, one record per settings panel. */
    public record Settings(Ccl ccl, Tw tw, NoGame noGame, IncompleteFallback incompleteFallback, Raid raid) {

        public record Ccl(boolean enabled, Set<String> blockedCcls) {}

        public record Tw(boolean enabled, Set<String> blockedTws) {}

        public record NoGame(String twitchGameId, String twitchGameName, Set<String> ccls,
                             boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd) {}

        public record IncompleteFallback(String twitchGameId, String twitchGameName, Set<String> ccls) {}

        /** {@code restoreGameMinutes}: how recent a game an outgoing raid puts back; null when off. */
        public record Raid(Integer restoreGameMinutes) {}
    }
}
