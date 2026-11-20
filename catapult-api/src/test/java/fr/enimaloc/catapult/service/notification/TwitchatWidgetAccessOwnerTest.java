package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import fr.enimaloc.catapult.repository.twitchat.TwitchatWidgetSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The owner of a widget, for what the widget sends on its owner's behalf, and whether the widget captures for debug. */
class TwitchatWidgetAccessOwnerTest {

    private final TwitchatWidgetSettingsRepository settings = mock(TwitchatWidgetSettingsRepository.class);
    private final WidgetTokenService widgetIds = mock(WidgetTokenService.class);
    private final DebugMemberRegistry debugMembers = mock(DebugMemberRegistry.class);
    private final TwitchatWidgetAccess access = new TwitchatWidgetAccess(settings, widgetIds,
            mock(TokenEncryptionService.class), mock(NotificationDeliveryDispatcher.class), debugMembers);
    private final UUID widgetId = UUID.randomUUID();
    private final UserAccount owner = new UserAccount();

    private void widget(boolean enabled) {
        owner.setId(UUID.randomUUID());
        when(widgetIds.resolve(widgetId)).thenReturn(Optional.of(owner));
        TwitchatWidgetSettings s = new TwitchatWidgetSettings();
        s.setEnabled(enabled);
        when(settings.findById(owner.getId())).thenReturn(Optional.of(s));
    }

    @Test
    void anEnabledWidgetNamesItsOwner() {
        widget(true);

        assertThat(access.owner(widgetId)).contains(owner.getId());
    }

    @Test
    void aDisabledWidgetIsNoWidget() {
        widget(false);

        assertThat(access.owner(widgetId)).isEmpty();
    }

    @Test
    void anUnknownWidgetHasNoOwner() {
        when(widgetIds.resolve(widgetId)).thenReturn(Optional.empty());

        assertThat(access.owner(widgetId)).isEmpty();
    }

    @Test
    void widgetConfigCarriesDebugCapture() {
        widget(true);
        when(debugMembers.modeOf(owner.getId())).thenReturn(Optional.empty());

        assertThat(access.config(widgetId)).hasValueSatisfying(c -> assertThat(c.debugCapture()).isFalse());

        when(debugMembers.modeOf(owner.getId()))
                .thenReturn(Optional.of(new DebugMemberRegistry.DebugMode(owner.getId(), false)));

        assertThat(access.config(widgetId)).hasValueSatisfying(c -> assertThat(c.debugCapture()).isTrue());
    }
}
