package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.common.dto.channel.TwitchExtensionsDto.Type;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.ExtensionSlotSetting;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.category.ExtensionLayoutApplier;
import fr.enimaloc.catapult.service.twitch.TwitchExtension;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DryRunExitListenerTest {

    final TwitchService twitch = mock(TwitchService.class);
    final GameStateService gameStateService = mock(GameStateService.class);
    final ExtensionLayoutApplier layouts = new ExtensionLayoutApplier(twitch, mock(ActivityLogService.class));
    final DryRunExitListener listener = new DryRunExitListener(gameStateService, layouts);

    @Test
    void extensionsAreReappliedAfterLeavingDryRun() {
        UserAccount member = new UserAccount();
        member.setId(UUID.randomUUID());
        List<ExtensionSlotSetting> layout = List.of(new ExtensionSlotSetting(Type.PANEL, 1, "ext-1", "Ext One"));
        when(twitch.getUserExtensions(member)).thenReturn(List.of(
                new TwitchExtension(true, "ext-1", "Ext One", List.of(), "1.0")));
        when(twitch.getActiveExtensions(member)).thenReturn(TwitchExtensionPlacement.empty());
        // during the dry run: held by the gate, but taken as in place
        layouts.applyOnSwitch(member, "binding:1", layout);

        listener.onDryRunEnded(new DryRunEndedEvent(member.getId()));
        // the same game, detected again after the dry run
        layouts.applyOnSwitch(member, "binding:1", layout);

        verify(twitch, times(2)).setActiveExtensions(eq(member), any());
        verify(gameStateService).forgetLastKnownGame(member.getId());
    }
}
