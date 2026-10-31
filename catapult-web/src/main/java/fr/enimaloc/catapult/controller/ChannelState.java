package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.GameDto;
import fr.enimaloc.catapult.common.dto.channel.MinecraftData;
import fr.enimaloc.catapult.common.dto.channel.SteamData;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.common.event.ChannelEvents;
import fr.enimaloc.catapult.event.BotStateChangedEvent;
import fr.enimaloc.catapult.event.ChannelConnectionChangedEvent;
import fr.enimaloc.catapult.event.ChannelLiveStateEvent;
import fr.enimaloc.catapult.event.ChannelSettingsUpdatedEvent;
import fr.enimaloc.catapult.event.ChannelUpdatedEvent;
import fr.enimaloc.catapult.event.DtddMappingChangedEvent;
import fr.enimaloc.catapult.event.binding.CustomBindingsUpdatedEvent;
import fr.enimaloc.catapult.event.binding.GameChangedEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftDisconnectedEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftSyncEvent;
import fr.enimaloc.catapult.event.steam.SteamConnectionStateEvent;
import fr.enimaloc.catapult.event.steam.SteamTokenDeletedEvent;
import fr.enimaloc.catapult.event.steam.SteamTokenSavedEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * A channel page's state as the live events that would bring a page to it, for the page to catch up on what it
 * missed while its stream was down (GET /spa/channel/{username}/state, see channel-events.js): each
 * {@code {name, data}} is what the channel's stream would send, named and shaped alike. Only what the page shows:
 * the bindings of the page it lists, the owner's cards for the owner alone, the DTDD mapping when the channel plays
 * a game IGDB knows.
 */
public final class ChannelState {

    /** One event: its name on the stream, and its payload. */
    public record Entry(String name, ChannelUpdatedEvent data) {
        static Entry of(ChannelUpdatedEvent event) {
            return new Entry(event.getClass().getSimpleName(), event);
        }
    }

    private ChannelState() {
    }

    /** {@code settings} and {@code dtdd} may be null: catapult-api failed to give them, the rest still counts. */
    public static List<Entry> of(ChannelPageData ch, UserSettingsDto settings, DtddMappingStatusDto dtdd) {
        String login = ch.channelUsername();
        List<ChannelUpdatedEvent> events = new ArrayList<>();
        events.add(new ChannelLiveStateEvent(login, ch.isLive()));
        events.add(new BotStateChangedEvent(login, ch.botEnabled()));
        GameDto game = ch.currentGame();
        events.add(game == null ? new GameChangedEvent(login, null, null, null)
                : new GameChangedEvent(login, game.bindingId(), game.sourceType(), game.sourceName()));
        if (ch.bindings() != null) {
            for (BindingDto binding : ch.bindings().content()) {
                events.addAll(ChannelEventRedisBridge.binding(login, binding));
            }
        }
        if (settings != null) {
            events.add(new ChannelSettingsUpdatedEvent(login, settings(settings)));
        }
        if (ch.steam() != null) {
            events.addAll(steam(login, ch.steam()));
        }
        if (ch.xbox() != null) {
            events.add(new ChannelConnectionChangedEvent(login, "XBOX", ch.xbox().connected()));
        }
        if (ch.minecraft() != null) {
            events.add(minecraft(login, ch.minecraft()));
        }
        CustomBindingsStateDto custom = ch.customBindings();
        if (custom != null) {
            events.add(new CustomBindingsUpdatedEvent(login, custom.bindings(),
                    custom.activeId() == null ? null : custom.activeId().toString()));
        }
        if (dtdd != null && dtdd.igdbId() != null) {
            events.add(new DtddMappingChangedEvent(login, dtdd.igdbId(), dtdd.current(), null,
                    dtdd.myPendingProposal() != null, dtdd.canValidateDirectly()));
        }
        return events.stream().map(Entry::of).toList();
    }

    /** A channel's settings as its live event carries them. */
    public static ChannelEvents.Settings settings(UserSettingsDto s) {
        return new ChannelEvents.Settings(
                new ChannelEvents.Settings.Ccl(s.cclFeatureEnabled(), s.blockedCcls()),
                new ChannelEvents.Settings.Tw(s.twFeatureEnabled(), s.blockedTws()),
                new ChannelEvents.Settings.NoGame(s.noGameTwitchGameId(), s.noGameTwitchGameName(), s.noGameCcls(),
                        s.applyDefaultOnStreamStart(), s.applyDefaultOnNoGame(), s.applyDefaultOnStreamEnd()),
                new ChannelEvents.Settings.IncompleteFallback(s.incompleteFallbackTwitchGameId(),
                        s.incompleteFallbackTwitchGameName(), s.incompleteFallbackCcls()),
                new ChannelEvents.Settings.Raid(s.raidRestoreGameMinutes()));
    }

    private static List<ChannelUpdatedEvent> steam(String login, SteamData steam) {
        return List.of(
                new SteamConnectionStateEvent(login, steam.connected(), steam.rateLimited(), steam.offlineMode(),
                        steam.profilePrivate()),
                steam.hasPersonalToken() ? new SteamTokenSavedEvent(login, steam.tokenShared())
                        : new SteamTokenDeletedEvent(login));
    }

    /** Without a link (no status, or NONE), the card shows what MinecraftDisconnectedEvent leaves it on. */
    private static ChannelUpdatedEvent minecraft(String login, MinecraftData minecraft) {
        String status = minecraft.status();
        return status == null || "NONE".equals(status) ? new MinecraftDisconnectedEvent(login)
                : new MinecraftSyncEvent(login, status, minecraft.minecraftName());
    }
}
