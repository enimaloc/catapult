package fr.enimaloc.catapult.service.battlenet;

import fr.enimaloc.catapult.common.dto.connect.BattleNetLinkStateResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.repository.battlenet.BattleNetServiceAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * A user's Battle.net link as the connect card shows it, shared by the connect endpoint and the
 * channel dashboard so both say the same thing: {@value #UNAVAILABLE} when the feature isn't
 * open to the user or no service account is usable (enabled and READY), the link's own status
 * when there is one, {@value #FULL} when every usable service account is full, else {@value #NONE}.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("battlenet.enabled")
public class BattleNetLinkStateService {

    public static final String NONE = "NONE";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    /** No usable service account can take a new friend: transient, unlike {@value #UNAVAILABLE}. */
    public static final String FULL = "FULL";

    private final BattleNetGateService gateService;
    private final BattleNetFriendLinkRepository linkRepository;
    private final BattleNetServiceAccountRepository accountRepository;

    public BattleNetLinkStateResponse state(UserAccount user) {
        if (!gateService.isAvailableFor(user)) {
            return of(UNAVAILABLE);
        }
        // No usable service account: unavailable, even to a linked user (their link can't be
        // managed until one is usable again).
        if (accountRepository.countByEnabledTrueAndAuthState(AuthState.READY) == 0) {
            return of(UNAVAILABLE);
        }
        // Loads the service account: open-in-view is disabled, callers read its tag outside a session.
        Optional<BattleNetFriendLink> link = linkRepository.findWithServiceAccountByUser(user);
        if (link.isPresent()) {
            return of(link.get());
        }
        // Usable accounts, all full: only the way to link is hidden; a linked user keeps their link.
        if (accountRepository.countByEnabledTrueAndAuthStateAndFriendLimitReachedFalse(AuthState.READY) == 0) {
            return of(FULL);
        }
        return of(NONE);
    }

    public static BattleNetLinkStateResponse of(String status) {
        return new BattleNetLinkStateResponse(status, null, null);
    }

    /** A link's state; its service account must be loaded. */
    public static BattleNetLinkStateResponse of(BattleNetFriendLink link) {
        return new BattleNetLinkStateResponse(
                link.getStatus().name(),
                link.getBattleTag(),
                link.getServiceAccount().getBattleTag());
    }
}
