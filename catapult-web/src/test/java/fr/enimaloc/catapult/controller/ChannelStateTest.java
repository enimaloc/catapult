package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.binding.CustomBindingDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingCurrentDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingProposalDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.GameDto;
import fr.enimaloc.catapult.common.dto.channel.MinecraftData;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.common.dto.channel.SteamData;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.common.dto.channel.XboxData;
import fr.enimaloc.catapult.common.event.ChannelEvents;
import fr.enimaloc.catapult.event.BotStateChangedEvent;
import fr.enimaloc.catapult.event.ChannelConnectionChangedEvent;
import fr.enimaloc.catapult.event.ChannelLiveStateEvent;
import fr.enimaloc.catapult.event.ChannelSettingsUpdatedEvent;
import fr.enimaloc.catapult.event.ChannelUpdatedEvent;
import fr.enimaloc.catapult.event.DtddMappingChangedEvent;
import fr.enimaloc.catapult.event.binding.BindingIgnoredStateEvent;
import fr.enimaloc.catapult.event.binding.BindingUpdatedEvent;
import fr.enimaloc.catapult.event.binding.CclStateEvent;
import fr.enimaloc.catapult.event.binding.CustomBindingsUpdatedEvent;
import fr.enimaloc.catapult.event.binding.GameChangedEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftDisconnectedEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftSyncEvent;
import fr.enimaloc.catapult.event.steam.SteamConnectionStateEvent;
import fr.enimaloc.catapult.event.steam.SteamTokenDeletedEvent;
import fr.enimaloc.catapult.event.steam.SteamTokenSavedEvent;
import fr.enimaloc.catapult.event.tw.TwEnabledStateEvent;
import fr.enimaloc.catapult.event.tw.TwUpdatedEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ChannelState: a channel page's state, as the live events that bring a page to it. */
class ChannelStateTest {

    private static final BindingDto VALHEIM = new BindingDto("b1", "AUTO", "STEAM", "Valheim", "1", "Valheim", false,
            true, Set.of("Violence"), true, true, Set.of("spoiler"));
    private static final UserSettingsDto SETTINGS = new UserSettingsDto(true, Set.of("Gambling"), "2", "Just Chatting",
            Set.of(), true, false, true, "3", "Art", Set.of("Drugs"), List.of(), false, Set.of(), List.of(), 30, true);

    private static ChannelPageData page(GameDto game, PagedBindings bindings, SteamData steam, XboxData xbox,
                                        MinecraftData minecraft, CustomBindingsStateDto custom) {
        return new ChannelPageData(new ChannelUserDto("1", "2", "enimaloc", "img"), "enimaloc", true, true, false,
                game, bindings, List.of(), Set.of(), List.of(), Set.of(), null, null, steam, xbox, minecraft, null,
                null, "uuid", custom);
    }

    private static List<ChannelUpdatedEvent> events(List<ChannelState.Entry> entries) {
        entries.forEach(entry -> assertThat(entry.name()).isEqualTo(entry.data().getClass().getSimpleName()));
        return entries.stream().map(ChannelState.Entry::data).toList();
    }

    @Test
    void everythingThePageShows_asItsEvents() {
        UUID active = UUID.randomUUID();
        CustomBindingDto art = new CustomBindingDto(active, "Art", "509660", "Art", List.of(), List.of(), false);
        DtddMappingCurrentDto current = new DtddMappingCurrentDto(7L, "Valheim", 0.8, false);
        ChannelPageData ch = page(new GameDto("b1", "Valheim", "STEAM"), new PagedBindings(0, 1, 1, List.of(VALHEIM)),
                new SteamData(true, true, true, false, false, false, 5), new XboxData(true),
                new MinecraftData("ACCEPTED", "jeb_", "bot"), new CustomBindingsStateDto(true, List.of(art), active, null));

        List<ChannelUpdatedEvent> events = events(ChannelState.of(ch, SETTINGS, new DtddMappingStatusDto(current,
                new DtddMappingProposalDto(UUID.randomUUID(), 9L, "wrong"), false, "42", true)));

        assertThat(events).containsExactly(
                new ChannelLiveStateEvent("enimaloc", true),
                new BotStateChangedEvent("enimaloc", false),
                new GameChangedEvent("enimaloc", "b1", "STEAM", "Valheim"),
                new BindingUpdatedEvent("enimaloc", "b1", "1", "Valheim", Set.of("Violence")),
                new CclStateEvent("enimaloc", "b1", true),
                new BindingIgnoredStateEvent("enimaloc", "b1", false),
                new TwEnabledStateEvent("enimaloc", "b1", true),
                new TwUpdatedEvent("enimaloc", "b1", Set.of("spoiler")),
                new ChannelSettingsUpdatedEvent("enimaloc", ChannelState.settings(SETTINGS)),
                new SteamConnectionStateEvent("enimaloc", true, false, false, false),
                new SteamTokenSavedEvent("enimaloc", true),
                new ChannelConnectionChangedEvent("enimaloc", "XBOX", true),
                new MinecraftSyncEvent("enimaloc", "ACCEPTED", "jeb_"),
                new CustomBindingsUpdatedEvent("enimaloc", List.of(art), active.toString()),
                new DtddMappingChangedEvent("enimaloc", "42", current, null, true, false));
    }

    @Test
    void whatThePageDoesntShow_isLeftOut() {
        ChannelPageData ch = page(null, null, null, null, null, null);

        assertThat(events(ChannelState.of(ch, null, null))).containsExactly(
                new ChannelLiveStateEvent("enimaloc", true),
                new BotStateChangedEvent("enimaloc", false),
                new GameChangedEvent("enimaloc", null, null, null));
        assertThat(events(ChannelState.of(ch, null, new DtddMappingStatusDto(null, null, false, null))))
                .hasSize(3);
    }

    @Test
    void withoutLinks_theCardsShowWhatTheirEventsLeaveThemOn() {
        CustomBindingsStateDto none = new CustomBindingsStateDto(true, List.of(), null, null);
        ChannelPageData ch = page(null, new PagedBindings(0, 0, 0, List.of()),
                new SteamData(false, false, false, false, false, false, 0), new XboxData(false),
                new MinecraftData(null, null, null), none);

        assertThat(events(ChannelState.of(ch, null, new DtddMappingStatusDto(null, null, true, "42")))).contains(
                new SteamTokenDeletedEvent("enimaloc"),
                new ChannelConnectionChangedEvent("enimaloc", "XBOX", false),
                new MinecraftDisconnectedEvent("enimaloc"),
                new CustomBindingsUpdatedEvent("enimaloc", List.of(), null),
                new DtddMappingChangedEvent("enimaloc", "42", null, null, false, true));
        assertThat(events(ChannelState.of(page(null, null, null, null, new MinecraftData("NONE", null, null), null),
                null, null))).contains(new MinecraftDisconnectedEvent("enimaloc"));
    }

    @Test
    void theSettings_asTheirLiveEventCarriesThem() {
        assertThat(ChannelState.settings(SETTINGS)).isEqualTo(new ChannelEvents.Settings(
                new ChannelEvents.Settings.Ccl(true, Set.of("Gambling")),
                new ChannelEvents.Settings.Tw(false, Set.of()),
                new ChannelEvents.Settings.NoGame("2", "Just Chatting", Set.of(), true, false, true),
                new ChannelEvents.Settings.IncompleteFallback("3", "Art", Set.of("Drugs")),
                new ChannelEvents.Settings.Raid(30)));
    }
}
