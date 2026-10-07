package fr.enimaloc.catapult.api.connect;

import fr.enimaloc.catapult.common.dto.LinkStateResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.minecraft.MinecraftFriendLink;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.service.minecraft.MinecraftFriendService;
import fr.enimaloc.catapult.service.minecraft.MinecraftGateService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/connect/minecraft")
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("minecraft.enabled")
public class ApiMinecraftConnectController {

    private final MinecraftFriendService friendService;
    private final UserAccountRepository userAccountRepository;
    private final MinecraftGateService gateService;
    private final MinecraftServiceAccountRepository accountRepository;

    /** Vérification immédiate : force la sync des liens puis retourne l'état à jour. */
    @PostMapping("/sync")
    public LinkStateResponse syncNow(@AuthenticationPrincipal Jwt jwt) {
        UserAccount user = gatedUser(jwt);
        friendService.syncFriendLinks();
        return friendService.getLink(user)
                .map(ApiMinecraftConnectController::toLinkState)
                .orElseGet(ApiMinecraftConnectController::none);
    }

    @GetMapping
    public LinkStateResponse status(@AuthenticationPrincipal Jwt jwt) {
        UserAccount user = currentUser(jwt);
        if (!gateService.isAvailableFor(user)) {
            return unavailable();
        }
        // Aucun compte de service actif : la feature est indisponible, y compris pour un
        // utilisateur déjà lié (son lien ne peut plus être géré tant qu'aucun compte ne tourne).
        if (accountRepository.countByEnabledTrue() == 0) {
            return unavailable();
        }
        Optional<MinecraftFriendLink> link = friendService.getLink(user);
        if (link.isPresent()) {
            return toLinkState(link.get());
        }
        // Comptes actifs mais tous pleins : on cache juste la possibilité de se lier,
        // un utilisateur déjà lié plus haut garde sa connexion (cas géré ci-dessus).
        if (accountRepository.countByEnabledTrueAndFriendLimitReachedFalse() == 0) {
            return full();
        }
        return none();
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
            return toLinkState(friendService.enroll(user, name));
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

    private static LinkStateResponse none() {
        return new LinkStateResponse("NONE", null, null);
    }

    private static LinkStateResponse unavailable() {
        return new LinkStateResponse("UNAVAILABLE", null, null);
    }

    /** Aucun compte de service actif ne peut accepter de nouvel ami — état transitoire, distinct de UNAVAILABLE. */
    private static LinkStateResponse full() {
        return new LinkStateResponse("FULL", null, null);
    }

    private static LinkStateResponse toLinkState(MinecraftFriendLink link) {
        return new LinkStateResponse(
                link.getStatus().name(),
                link.getMinecraftName(),
                link.getServiceAccount().getMinecraftUsername());
    }
}
