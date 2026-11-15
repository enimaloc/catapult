package fr.enimaloc.catapult.service.epic;

import fr.enimaloc.catapult.common.dto.connect.EpicLinkStateResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.epic.EpicFriendLink;
import fr.enimaloc.catapult.domain.epic.EpicFriendLink.Status;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.epic.EpicFriendLinkRepository;
import fr.enimaloc.catapult.repository.epic.EpicServiceAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * A user's Epic link as the connect card shows it: {@value #UNAVAILABLE} when the feature isn't open to the user or
 * no service account is usable (enabled and READY), the link's own status when there is one, {@value #FULL} when
 * every usable service account is full, else {@value #NONE}.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("epic.enabled")
public class EpicLinkStateService {

    public static final String NONE = "NONE";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    /** No usable service account can take a new friend: transient, unlike {@value #UNAVAILABLE}. */
    public static final String FULL = "FULL";

    private final EpicGateService gateService;
    private final EpicFriendLinkRepository linkRepository;
    private final EpicServiceAccountRepository accountRepository;

    public EpicLinkStateResponse state(UserAccount user) {
        if (!gateService.isAvailableFor(user)) {
            return of(UNAVAILABLE);
        }
        // No usable service account: unavailable, even to a linked user (their link can't be managed until one is
        // usable again).
        if (accountRepository.countByEnabledTrueAndAuthState(AuthState.READY) == 0) {
            return of(UNAVAILABLE);
        }
        // Loads the service account: open-in-view is disabled, callers read its name outside a session.
        Optional<EpicFriendLink> link = linkRepository.findWithServiceAccountByUser(user);
        if (link.isPresent()) {
            return of(link.get());
        }
        // Usable accounts, all full: only the way to link is hidden; a linked user keeps their link.
        if (accountRepository.countByEnabledTrueAndAuthStateAndFriendLimitReachedFalse(AuthState.READY) == 0) {
            return of(FULL);
        }
        return of(NONE);
    }

    public static EpicLinkStateResponse of(String status) {
        return new EpicLinkStateResponse(status, null, null);
    }

    /** A link's state; its service account must be loaded. The bot's name shows while the streamer has to act. */
    public static EpicLinkStateResponse of(EpicFriendLink link) {
        boolean waiting = link.getStatus() == Status.PENDING || link.getStatus() == Status.INVITE_REJECTED;
        return new EpicLinkStateResponse(
                link.getStatus().name(),
                link.getDisplayName(),
                waiting ? link.getServiceAccount().getDisplayName() : null);
    }
}
