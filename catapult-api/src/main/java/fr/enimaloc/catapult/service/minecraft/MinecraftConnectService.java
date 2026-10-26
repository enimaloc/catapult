package fr.enimaloc.catapult.service.minecraft;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** A member's own Minecraft link: its state, enrolling a player name and leaving, each told to the dashboards. */
@Service
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("minecraft.enabled")
public class MinecraftConnectService {

    private final MinecraftFriendService friendService;
    private final UserAccountRepository userAccountRepository;
    private final MinecraftGateService gateService;
    private final MinecraftLinkStateService linkStates;
    private final ChannelEventPublisher channelEventPublisher;

    /** Vérification immédiate : force la sync des liens puis retourne l'état à jour. */
    public LinkStateResponse sync(UUID caller) {
        UserAccount user = gatedUser(caller);
        friendService.syncFriendLinks();
        return published(user, friendService.getLink(user)
                .map(MinecraftLinkStateService::of)
                .orElseGet(MinecraftConnectService::none));
    }

    public LinkStateResponse status(UUID caller) {
        return linkStates.state(currentUser(caller));
    }

    public LinkStateResponse enroll(UUID caller, String rawName) {
        UserAccount user = gatedUser(caller); // le gate prime sur la validation métier (403 avant 400)
        String name = rawName == null ? null : rawName.trim();
        if (name == null || name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A Minecraft username is required");
        }
        try {
            return published(user, MinecraftLinkStateService.of(friendService.enroll(user, name)));
        } catch (MinecraftFriendService.MinecraftEnrollmentException e) {
            throw switch (e.getReason()) {
                case UNKNOWN_PLAYER -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown Minecraft player");
                case NO_CAPACITY, NO_ACCOUNT_AVAILABLE ->
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No Minecraft service account has room left, contact an administrator");
            };
        }
    }

    public void unenroll(UUID caller) {
        UserAccount user = gatedUser(caller);
        friendService.unenroll(user);
        published(user, none());
    }

    /** {@code state}, once told to the member's dashboards. */
    private LinkStateResponse published(UserAccount user, LinkStateResponse state) {
        channelEventPublisher.minecraftLinkChanged(user.getId(), state.status(), state.minecraftName());
        return state;
    }

    private static LinkStateResponse none() {
        return MinecraftLinkStateService.of(MinecraftLinkStateService.NONE);
    }

    private UserAccount currentUser(UUID caller) {
        return userAccountRepository.findById(caller)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));
    }

    private UserAccount gatedUser(UUID caller) {
        UserAccount user = currentUser(caller);
        if (!gateService.isAvailableFor(user)) {
            throw new ApiProblem(ErrorCode.FEATURE_DISABLED, "Minecraft is not available for this account");
        }
        return user;
    }
}
