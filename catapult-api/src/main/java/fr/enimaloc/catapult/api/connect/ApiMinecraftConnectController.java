package fr.enimaloc.catapult.api.connect;

import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.minecraft.MinecraftFriendService;
import fr.enimaloc.catapult.service.minecraft.MinecraftGateService;
import fr.enimaloc.catapult.service.minecraft.MinecraftLinkStateService;
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
    private final MinecraftGateService gateService;
    private final MinecraftLinkStateService linkStates;

    /** Vérification immédiate : force la sync des liens puis retourne l'état à jour. */
    @PostMapping("/sync")
    public LinkStateResponse syncNow(@AuthenticationPrincipal Jwt jwt) {
        UserAccount user = gatedUser(jwt);
        friendService.syncFriendLinks();
        return friendService.getLink(user)
                .map(MinecraftLinkStateService::of)
                .orElseGet(() -> MinecraftLinkStateService.of(MinecraftLinkStateService.NONE));
    }

    @GetMapping
    public LinkStateResponse status(@AuthenticationPrincipal Jwt jwt) {
        return linkStates.state(currentUser(jwt));
    }

    @PostMapping
    public LinkStateResponse enroll(@AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, String> body) {
        UserAccount user = gatedUser(jwt); // le gate prime sur la validation métier (403 avant 400)
        String name = body.get("name");
        name = name == null ? null : name.trim();
        if (name == null || name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pseudo requis");
        }
        try {
            return MinecraftLinkStateService.of(friendService.enroll(user, name));
        } catch (MinecraftFriendService.MinecraftEnrollmentException e) {
            throw switch (e.getReason()) {
                case UNKNOWN_PLAYER -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Joueur introuvable");
                case NO_CAPACITY, NO_ACCOUNT_AVAILABLE ->
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Aucune capacité disponible, contacte un administrateur");
            };
        }
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unenroll(@AuthenticationPrincipal Jwt jwt) {
        friendService.unenroll(gatedUser(jwt));
    }

    private UserAccount currentUser(Jwt jwt) {
        return userAccountRepository.findById(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Utilisateur inconnu"));
    }

    private UserAccount gatedUser(Jwt jwt) {
        UserAccount user = currentUser(jwt);
        if (!gateService.isAvailableFor(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Feature Minecraft indisponible");
        }
        return user;
    }
}
