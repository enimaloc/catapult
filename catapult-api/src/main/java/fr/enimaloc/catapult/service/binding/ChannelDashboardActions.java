package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.common.dto.channel.UpdateBindingRequest;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.ExtensionSlotSetting;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

/**
 * What a channel's dashboard does to a binding, and the manual game re-check: each change is made by
 * {@link BindingService}, then published to the channel's dashboards. Not transactional: it publishes after
 * {@code BindingService}'s commit.
 */
@Service
@RequiredArgsConstructor
public class ChannelDashboardActions {

    private final BindingService bindingService;
    private final SchedulerService schedulerService;
    private final GameStateService gameStateService;
    private final ChannelEventPublisher channelEventPublisher;

    public void toggleCcl(UserAccount channel, UUID bindingId, boolean enabled) {
        bindingService.toggleCclEnabled(channel, bindingId, enabled);
        publishBinding(channel, bindingId);
    }

    public void toggleIgnored(UserAccount channel, UUID bindingId, boolean ignored) {
        bindingService.toggleIgnored(channel, bindingId, ignored);
        publishBinding(channel, bindingId);
    }

    public void delete(UserAccount channel, UUID bindingId) {
        bindingService.deleteBinding(channel, bindingId);
        channelEventPublisher.bindingDeleted(channel.getId(), bindingId);
    }

    public void update(UserAccount channel, UUID bindingId, UpdateBindingRequest body) {
        Set<String> ccls = body.ccls() != null ? body.ccls() : Set.of();
        bindingService.updateBinding(channel, bindingId, body.twitchGameId(), body.twitchGameName(), ccls, false);
        publishBinding(channel, bindingId);
    }

    public void updateExtensionSlots(UserAccount channel, UUID bindingId, Set<ExtensionSlotSetting> slots) {
        bindingService.updateExtensionSlots(channel, bindingId, slots);
        publishBinding(channel, bindingId);
    }

    public void setTws(UserAccount owner, UUID bindingId, Set<String> tws) {
        bindingService.setTwsForBinding(owner, bindingId, tws == null ? Set.of() : tws);
        publishBinding(owner, bindingId);
    }

    public void resetTws(UserAccount owner, UUID bindingId) {
        bindingService.resetTws(owner, bindingId);
        publishBinding(owner, bindingId);
    }

    public void toggleTwEnabled(UserAccount owner, UUID bindingId, boolean enabled) {
        bindingService.toggleTwEnabled(owner, bindingId, enabled);
        publishBinding(owner, bindingId);
    }

    public void recheckGame(UserAccount user) {
        if (user.getStatus() != UserAccount.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account is not active");
        }
        schedulerService.triggerManualCheck(user);
        // The dashboards are told what is detected now, changed or not: the check's own event only
        // goes out on a change, and catapult-web no longer publishes one itself.
        channelEventPublisher.gameDetected(user.getId(), gameStateService.getLastKnownGame(user).orElse(null));
    }

    /** Tells the channel's dashboards about a binding's new state (nothing if it's gone). */
    private void publishBinding(UserAccount channel, UUID bindingId) {
        bindingService.findBinding(channel, bindingId)
                .ifPresent(binding -> channelEventPublisher.bindingUpserted(channel.getId(), binding));
    }
}
