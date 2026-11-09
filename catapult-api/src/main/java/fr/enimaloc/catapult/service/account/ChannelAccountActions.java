package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.error.ErrorCode;
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

    /** Starts the deletion only when the typed username matches, ignoring case; a mismatch is a 400. */
    public void requestDeletion(UserAccount channelUser, String confirmUsername) {
        if (!channelUser.getTwitchUsername().equalsIgnoreCase(confirmUsername)) {
            throw new ApiProblem(ErrorCode.BAD_REQUEST, "The confirmation does not match the channel's username");
        }
        accountService.initiateAccountDeletion(channelUser);
    }

    public void disconnect(UserAccount channelUser, OAuthToken.Provider provider) {
        accountService.disconnectProvider(channelUser, provider);
        channelEventPublisher.connectionChanged(channelUser.getId(),
                new ProviderConnectionsDto(provider.name(), false, null));
    }
}
