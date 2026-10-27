package fr.enimaloc.catapult.service.minecraft;

import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.minecraft.MinecraftFriendLink;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * A user's Minecraft link as the connect card shows it, shared by the connect endpoint and the
 * channel dashboard so both say the same thing: {@value #UNAVAILABLE} when the feature isn't
 * open to the user or no service account runs, the link's own status when there is one,
 * {@value #FULL} when every running service account is full, else {@value #NONE}.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("minecraft.enabled")
public class MinecraftLinkStateService {

    public static final String NONE = "NONE";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    /** No running service account can take a new friend: transient, unlike {@value #UNAVAILABLE}. */
    public static final String FULL = "FULL";

    private final MinecraftFriendService friendService;
    private final MinecraftGateService gateService;
    private final MinecraftServiceAccountRepository accountRepository;

    public LinkStateResponse state(UserAccount user) {
        if (!gateService.isAvailableFor(user)) {
            return of(UNAVAILABLE);
        }
        // No running service account: unavailable, even to a linked user (their link can't be
        // managed until one runs again).
        if (accountRepository.countByEnabledTrue() == 0) {
            return of(UNAVAILABLE);
        }
        Optional<MinecraftFriendLink> link = friendService.getLink(user);
        if (link.isPresent()) {
            return of(link.get());
        }
        // Running accounts, all full: only the way to link is hidden, a linked user keeps their
        // link (handled above).
        if (accountRepository.countByEnabledTrueAndFriendLimitReachedFalse() == 0) {
            return of(FULL);
        }
        return of(NONE);
    }

    public static LinkStateResponse of(String status) {
        return new LinkStateResponse(status, null, null);
    }

    /** A link's state; its service account must be loaded (getLink() fetches it). */
    public static LinkStateResponse of(MinecraftFriendLink link) {
        return new LinkStateResponse(
                link.getStatus().name(),
                link.getMinecraftName(),
                link.getServiceAccount().getMinecraftUsername());
    }
}
