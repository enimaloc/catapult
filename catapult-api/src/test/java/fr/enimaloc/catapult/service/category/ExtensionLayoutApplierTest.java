package fr.enimaloc.catapult.service.category;

import fr.enimaloc.catapult.common.dto.channel.TwitchExtensionsDto.Type;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.ExtensionSlotSetting;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.twitch.TwitchExtension;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement.Slot;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExtensionLayoutApplierTest {

    @Mock private TwitchService twitchService;
    @Mock private ActivityLogService activityLogService;

    private ExtensionLayoutApplier applier;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        applier = new ExtensionLayoutApplier(twitchService, activityLogService);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    private static ExtensionSlotSetting setting(Type type, int slot, String id) {
        return new ExtensionSlotSetting(type, slot, id, id == null ? null : "name-" + id);
    }

    private static TwitchExtension installed(String id, String version, boolean canActivate) {
        return new TwitchExtension(canActivate, id, "name-" + id, List.of(), version);
    }

    private TwitchExtensionPlacement sent() {
        ArgumentCaptor<TwitchExtensionPlacement> captor = ArgumentCaptor.forClass(TwitchExtensionPlacement.class);
        verify(twitchService).setActiveExtensions(eq(user), captor.capture());
        return captor.getValue();
    }

    @Test
    void empty_layout_calls_twitch_not_at_all() {
        applier.applyOnSwitch(user, "binding:1", List.of());

        verifyNoInteractions(twitchService);
    }

    @Test
    void none_deactivates_the_slot() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of());

        applier.applyOnSwitch(user, "k", List.of(setting(Type.PANEL, 2, null)));

        TwitchExtensionPlacement placement = sent();
        assertThat(placement.panel().second()).isEqualTo(Slot.empty());
        assertThat(placement.panel().first()).isNull();
        assertThat(placement.panel().third()).isNull();
        assertThat(placement.component()).isNull();
        assertThat(placement.overlay()).isNull();
    }

    @Test
    void extension_uses_the_installed_version() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of(installed("p1", "2.0", true)));

        applier.applyOnSwitch(user, "k", List.of(setting(Type.PANEL, 1, "p1")));

        assertThat(sent().panel().first()).isEqualTo(Slot.of("p1", "2.0"));
    }

    @Test
    void uninstalled_extension_is_skipped_and_logged() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of(installed("p1", "1.0", true)));

        applier.applyOnSwitch(user, "k",
                List.of(setting(Type.PANEL, 1, "p1"), setting(Type.OVERLAY, 1, "gone")));

        TwitchExtensionPlacement placement = sent();
        assertThat(placement.panel().first()).isEqualTo(Slot.of("p1", "1.0"));
        assertThat(placement.overlay()).isNull();
        verify(activityLogService, times(1)).addEntry(eq(user.getId()), eq("WARN"), any());
    }

    @Test
    void component_keeps_the_current_position() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of(installed("c1", "3", true)));
        when(twitchService.getActiveExtensions(user)).thenReturn(TwitchExtensionPlacement.update()
                .component(1, Slot.at("old", "1", 10, 20)).build());

        applier.applyOnSwitch(user, "k", List.of(setting(Type.COMPONENT, 1, "c1")));

        assertThat(sent().component().first()).isEqualTo(Slot.at("c1", "3", 10, 20));
    }

    @Test
    void component_on_an_empty_slot_goes_to_origin() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of(installed("c1", "3", true)));
        when(twitchService.getActiveExtensions(user)).thenReturn(TwitchExtensionPlacement.empty());

        applier.applyOnSwitch(user, "k", List.of(setting(Type.COMPONENT, 2, "c1")));

        assertThat(sent().component().second()).isEqualTo(Slot.at("c1", "3", 0, 0));
    }

    @Test
    void same_key_twice_applies_once() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of());
        List<ExtensionSlotSetting> layout = List.of(setting(Type.PANEL, 1, null));

        applier.applyOnSwitch(user, "k", layout);
        applier.applyOnSwitch(user, "k", layout);

        verify(twitchService, times(1)).setActiveExtensions(eq(user), any());
    }

    @Test
    void applyIfCurrent_ignores_another_configuration() {
        applier.applyOnSwitch(user, "a", List.of());

        applier.applyIfCurrent(user, "b", List.of(setting(Type.PANEL, 1, null)));

        verify(twitchService, never()).setActiveExtensions(any(), any());
    }

    @Test
    void applyIfCurrent_applies_the_current_one() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of());
        applier.applyOnSwitch(user, "a", List.of());

        applier.applyIfCurrent(user, "a", List.of(setting(Type.PANEL, 1, null)));

        verify(twitchService).setActiveExtensions(eq(user), any());
    }

    @Test
    void restore_lets_the_same_key_apply_again() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of());
        List<ExtensionSlotSetting> layout = List.of(setting(Type.PANEL, 1, null));

        applier.applyOnSwitch(user, "k", layout);
        applier.restore(user);
        applier.applyOnSwitch(user, "k", layout);

        verify(twitchService, times(2)).setActiveExtensions(eq(user), any());
    }

    private List<TwitchExtensionPlacement> allSent(int count) {
        ArgumentCaptor<TwitchExtensionPlacement> captor = ArgumentCaptor.forClass(TwitchExtensionPlacement.class);
        verify(twitchService, times(count)).setActiveExtensions(eq(user), captor.capture());
        return captor.getAllValues();
    }

    @Test
    void restore_puts_back_what_the_layout_replaced_at_its_installed_version() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of(installed("game", "1", true),
                installed("mine", "2.1", true)));
        when(twitchService.getActiveExtensions(user)).thenReturn(TwitchExtensionPlacement.update()
                .panel(1, new Slot(true, "mine", "Mine", "2.0", null, null))
                .overlay(1, Slot.empty()).component(1, Slot.empty()).build());

        applier.applyOnSwitch(user, "binding:1", List.of(setting(Type.PANEL, 1, "game")));
        applier.restore(user);

        List<TwitchExtensionPlacement> sent = allSent(2);
        assertThat(sent.get(0).panel().first()).isEqualTo(Slot.of("game", "1"));
        assertThat(sent.get(1).panel().first()).isEqualTo(Slot.of("mine", "2.1"));
        assertThat(sent.get(1).overlay()).isNull();
        assertThat(sent.get(1).component()).isNull();
    }

    @Test
    void restore_empties_a_slot_that_was_empty_and_puts_a_component_back_where_it_was() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of(installed("game", "1", true),
                installed("comp", "4", true)));
        when(twitchService.getActiveExtensions(user)).thenReturn(TwitchExtensionPlacement.update()
                .panel(2, Slot.empty()).component(1, new Slot(true, "comp", "Comp", "4", 30, 40)).build());

        applier.applyOnSwitch(user, "binding:1",
                List.of(setting(Type.PANEL, 2, "game"), setting(Type.COMPONENT, 1, null)));
        applier.restore(user);

        TwitchExtensionPlacement back = allSent(2).get(1);
        assertThat(back.panel().second()).isEqualTo(Slot.empty());
        assertThat(back.component().first()).isEqualTo(Slot.at("comp", "4", 30, 40));
    }

    @Test
    void the_next_configuration_gets_back_the_slots_it_keeps_but_not_the_ones_it_sets() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of(installed("a", "1", true),
                installed("b", "1", true), installed("mine", "1", true)));
        when(twitchService.getActiveExtensions(user)).thenReturn(TwitchExtensionPlacement.update()
                .panel(1, Slot.of("mine", "1")).panel(2, Slot.of("mine", "1")).build());

        applier.applyOnSwitch(user, "binding:A", List.of(setting(Type.PANEL, 1, "a"), setting(Type.PANEL, 2, "a")));
        applier.applyOnSwitch(user, "binding:B", List.of(setting(Type.PANEL, 2, "b")));
        applier.restore(user);

        List<TwitchExtensionPlacement> sent = allSent(3);
        assertThat(sent.get(1).panel().first()).isEqualTo(Slot.of("mine", "1"));
        assertThat(sent.get(1).panel().second()).isEqualTo(Slot.of("b", "1"));
        // Slot 2 still holds what was there before game A, not game A's extension
        assertThat(sent.get(2).panel().first()).isNull();
        assertThat(sent.get(2).panel().second()).isEqualTo(Slot.of("mine", "1"));
    }

    @Test
    void the_next_configuration_puts_its_extensions_on_top_of_the_restored_ones() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of(installed("game", "1", true),
                installed("nogame", "1", true), installed("mine", "1", true)));
        when(twitchService.getActiveExtensions(user)).thenReturn(TwitchExtensionPlacement.update()
                .panel(1, Slot.of("mine", "1")).panel(2, Slot.empty())
                .component(1, Slot.at("mine", "1", 5, 6)).build());
        applier.applyOnSwitch(user, "binding:1", List.of(setting(Type.PANEL, 1, "game"),
                setting(Type.PANEL, 2, "game"), setting(Type.COMPONENT, 1, "game")));
        // What Twitch holds once the game's layout is in place
        when(twitchService.getActiveExtensions(user)).thenReturn(TwitchExtensionPlacement.update()
                .panel(1, Slot.of("game", "1")).panel(2, Slot.of("game", "1"))
                .component(1, Slot.at("game", "1", 90, 90)).build());

        applier.applyOnSwitch(user, ExtensionLayoutApplier.NO_GAME_KEY,
                List.of(setting(Type.PANEL, 2, "nogame"), setting(Type.COMPONENT, 1, "nogame")));

        TwitchExtensionPlacement next = allSent(2).get(1);
        assertThat(next.panel().first()).isEqualTo(Slot.of("mine", "1"));
        assertThat(next.panel().second()).isEqualTo(Slot.of("nogame", "1"));
        // At the position the component had before the game moved it
        assertThat(next.component().first()).isEqualTo(Slot.at("nogame", "1", 5, 6));
    }

    @Test
    void restore_without_any_change_calls_twitch_not_at_all() {
        applier.restore(user);

        verifyNoInteractions(twitchService);
    }

    @Test
    void an_unreadable_channel_is_logged_and_nothing_is_put_back() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of(installed("game", "1", true)));
        when(twitchService.getActiveExtensions(user)).thenReturn(TwitchExtensionPlacement.unreadable());

        applier.applyOnSwitch(user, "binding:1", List.of(setting(Type.PANEL, 1, "game")));
        applier.restore(user);

        verify(twitchService, times(1)).setActiveExtensions(eq(user), any());
        verify(activityLogService).addEntry(eq(user.getId()), eq("WARN"),
                org.mockito.ArgumentMatchers.contains("will not be put back"));
    }

    @Test
    void an_extension_uninstalled_since_is_given_up_and_logged() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of(installed("game", "1", true),
                installed("mine", "1", true)));
        when(twitchService.getActiveExtensions(user)).thenReturn(TwitchExtensionPlacement.update()
                .panel(1, Slot.of("mine", "1")).build());
        applier.applyOnSwitch(user, "binding:1", List.of(setting(Type.PANEL, 1, "game")));
        when(twitchService.getUserExtensions(user)).thenReturn(List.of(installed("game", "1", true)));

        applier.restore(user);
        applier.restore(user);

        verify(twitchService, times(1)).setActiveExtensions(eq(user), any());
        verify(activityLogService).addEntry(eq(user.getId()), eq("WARN"),
                org.mockito.ArgumentMatchers.contains("not put back"));
    }

    @Test
    void a_failed_restore_is_tried_again_next_time() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of(installed("game", "1", true),
                installed("mine", "1", true)));
        when(twitchService.getActiveExtensions(user)).thenReturn(TwitchExtensionPlacement.update()
                .panel(1, Slot.of("mine", "1")).build());
        applier.applyOnSwitch(user, "binding:1", List.of(setting(Type.PANEL, 1, "game")));
        doThrow(new IllegalStateException("down")).doReturn(null)
                .when(twitchService).setActiveExtensions(eq(user), any());

        applier.restore(user);
        applier.restore(user);

        assertThat(allSent(3).get(2).panel().first()).isEqualTo(Slot.of("mine", "1"));
    }

    @Test
    void twitch_error_is_logged_not_thrown() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of());
        doThrow(new IllegalStateException("boom")).when(twitchService).setActiveExtensions(eq(user), any());

        assertThatCode(() -> applier.applyOnSwitch(user, "k", List.of(setting(Type.PANEL, 1, null))))
                .doesNotThrowAnyException();

        verify(activityLogService).addEntry(eq(user.getId()), eq("WARN"), any());
    }

    @Test
    void twitch_error_without_message_is_logged_with_its_type() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of());
        doThrow(new IllegalStateException()).when(twitchService).setActiveExtensions(eq(user), any());

        applier.applyOnSwitch(user, "k", List.of(setting(Type.PANEL, 1, null)));

        verify(activityLogService).addEntry(user.getId(), "WARN",
                "Twitch extensions not applied: java.lang.IllegalStateException");
    }

    @Test
    void unreadable_installed_list_logs_one_entry_and_still_applies_the_none_slots() {
        when(twitchService.getUserExtensions(user)).thenReturn(List.of());

        applier.applyOnSwitch(user, "k", List.of(setting(Type.PANEL, 1, "p1"), setting(Type.PANEL, 2, "p2"),
                setting(Type.OVERLAY, 1, null)));

        verify(activityLogService, times(1)).addEntry(eq(user.getId()), eq("WARN"),
                org.mockito.ArgumentMatchers.contains("could not be read"));
        verify(activityLogService, times(1)).addEntry(eq(user.getId()), eq("WARN"), any());
        TwitchExtensionPlacement placement = sent();
        assertThat(placement.overlay().first()).isEqualTo(Slot.empty());
        assertThat(placement.panel()).isNull();
    }
}
