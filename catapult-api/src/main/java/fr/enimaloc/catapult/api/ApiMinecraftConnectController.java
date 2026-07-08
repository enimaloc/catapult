package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.MinecraftFriendService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/connect/minecraft")
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("minecraft.enabled")
public class ApiMinecraftConnectController {

    private final MinecraftFriendService friendService;
    private final UserAccountRepository userAccountRepository;

    public record LinkStateResponse(String status, String minecraftName, String serviceAccountUsername) {
        static LinkStateResponse none() {
            return new LinkStateResponse("NONE", null, null);
        }

        static LinkStateResponse of(MinecraftFriendLink link) {
            return new LinkStateResponse(
                    link.getStatus().name(),
                    link.getMinecraftName(),
                    link.getServiceAccount().getMinecraftUsername());
        }
    }

    @GetMapping
    public LinkStateResponse status(@AuthenticationPrincipal Jwt jwt) {
        return friendService.getLink(currentUser(jwt))
                .map(LinkStateResponse::of)
                .orElseGet(LinkStateResponse::none);
    }

    @PostMapping
    public LinkStateResponse enroll(@AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pseudo requis");
        }
        try {
            return LinkStateResponse.of(friendService.enroll(currentUser(jwt), name.trim()));
        } catch (MinecraftFriendService.MinecraftEnrollmentException e) {
            throw switch (e.getReason()) {
                case UNKNOWN_PLAYER -> new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
                case NO_CAPACITY, NO_ACCOUNT_AVAILABLE ->
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
            };
        }
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unenroll(@AuthenticationPrincipal Jwt jwt) {
        friendService.unenroll(currentUser(jwt));
    }

    private UserAccount currentUser(Jwt jwt) {
        return userAccountRepository.findById(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Utilisateur inconnu"));
    }
}
