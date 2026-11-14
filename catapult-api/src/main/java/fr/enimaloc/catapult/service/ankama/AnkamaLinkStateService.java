package fr.enimaloc.catapult.service.ankama;

import fr.enimaloc.catapult.common.dto.connect.AnkamaLinkStateResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink;
import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink.Status;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.ankama.AnkamaFriendLinkRepository;
import fr.enimaloc.catapult.repository.ankama.AnkamaServiceAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * A user's Ankama link as the connect card shows it: {@value #UNAVAILABLE} when the feature isn't open to the user or
 * no service account is usable (enabled and READY), the link's own status when there is one, {@value #FULL} when
 * every usable service account is full, else {@value #NONE}.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("ankama.enabled")
public class AnkamaLinkStateService {

    public static final String NONE = "NONE";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    /** No usable service account can take a new friend: transient, unlike {@value #UNAVAILABLE}. */
    public static final String FULL = "FULL";

    private final AnkamaGateService gateService;
    private final AnkamaFriendLinkRepository linkRepository;
    private final AnkamaServiceAccountRepository accountRepository;

    public AnkamaLinkStateResponse state(UserAccount user) {
        if (!gateService.isAvailableFor(user)) {
            return of(UNAVAILABLE);
        }
        // No usable service account: unavailable, even to a linked user (their link can't be managed until one is
        // usable again).
        if (accountRepository.countByEnabledTrueAndAuthState(AuthState.READY) == 0) {
            return of(UNAVAILABLE);
        }
        // Loads the service account: open-in-view is disabled, callers read its tag outside a session.
        Optional<AnkamaFriendLink> link = linkRepository.findWithServiceAccountByUser(user);
        if (link.isPresent()) {
            return of(link.get());
        }
        // Usable accounts, all full: only the way to link is hidden; a linked user keeps their link.
        if (accountRepository.countByEnabledTrueAndAuthStateAndFriendLimitReachedFalse(AuthState.READY) == 0) {
            return of(FULL);
        }
        return of(NONE);
    }

    public static AnkamaLinkStateResponse of(String status) {
        return new AnkamaLinkStateResponse(status, null, null);
    }

    /** A link's state; its service account must be loaded. The bot's tag shows while the streamer has to act. */
    public static AnkamaLinkStateResponse of(AnkamaFriendLink link) {
        boolean waiting = link.getStatus() == Status.PENDING || link.getStatus() == Status.INVITE_REJECTED;
        return new AnkamaLinkStateResponse(
                link.getStatus().name(),
                link.displayTag(),
                waiting ? link.getServiceAccount().displayTag() : null);
    }
}
