package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.connections.ProviderConnectionsDto;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * What an owner does to their account from the dashboard. A bean of its own, not transactional, so that
 * {@link AccountService}'s transactions commit before the dashboards are told.
 */
@Service
@RequiredArgsConstructor
public class ChannelAccountActions {

    private final AccountService accountService;
    private final ChannelEventPublisher channelEventPublisher;

    /** Starts the deletion only when the typed username matches; a mismatch is silently ignored. */
    public void requestDeletion(UserAccount channelUser, String confirmUsername) {
        if (channelUser.getTwitchUsername().equalsIgnoreCase(confirmUsername)) {
            accountService.initiateAccountDeletion(channelUser);
        }
    }

    public void disconnect(UserAccount channelUser, OAuthToken.Provider provider) {
        accountService.disconnectProvider(channelUser, provider);
        channelEventPublisher.connectionChanged(channelUser.getId(),
                new ProviderConnectionsDto(provider.name(), false, null));
    }
}
