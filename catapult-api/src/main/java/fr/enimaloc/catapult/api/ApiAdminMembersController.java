package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.AccountService;
import fr.enimaloc.catapult.service.AdminMigrationService;
import fr.enimaloc.catapult.service.StreamStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/admin/members")
@RequiredArgsConstructor
public class ApiAdminMembersController {

    private final UserAccountRepository userAccountRepository;
    private final StreamStateService streamStateService;
    private final AccountService accountService;
    private final AdminMigrationService adminMigrationService;
    private final Environment environment;

    @GetMapping
    public MembersPageData page() {
        List<UserAccount> members = userAccountRepository.findAll();
        Map<UUID, Boolean> liveStatus = members.stream()
                .collect(Collectors.toMap(UserAccount::getId, streamStateService::isLive));
        boolean isMockProfile = Arrays.asList(environment.getActiveProfiles()).contains("mock");
        return new MembersPageData(members, liveStatus, isMockProfile);
    }

    @PostMapping("/{id}/bot/toggle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleBot(@PathVariable UUID id) {
        UserAccount user = findOrThrow(id);
        user.setBotEnabled(!user.isBotEnabled());
        userAccountRepository.save(user);
    }

    @PostMapping("/{id}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UserAccount user = findOrThrow(id);
        if (user.isSystemAccount()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String callerTwitchId = jwt.getClaimAsString("twitchId");
        if (user.getTwitchId().equals(callerTwitchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        log.info("Admin {} deleted account {} (twitchId={})", callerTwitchId, user.getId(), user.getTwitchId());
        accountService.deleteAccountImmediately(user);
    }

    @PostMapping("/{id}/steam/unlink")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkSteam(@PathVariable UUID id) {
        UserAccount user = findOrThrow(id);
        if (user.getSteamId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        accountService.disconnectProvider(user, OAuthToken.Provider.STEAM);
    }

    @PostMapping("/{id}/twitch/unlink")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkTwitch(@PathVariable UUID id) {
        UserAccount user = findOrThrow(id);
        if (user.getStatus() != UserAccount.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        accountService.unlinkTwitch(user);
    }

    @PostMapping("/{id}/migrate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void migrateData(@PathVariable UUID id, @RequestBody MigrateRequest body) {
        UserAccount source = findOrThrow(id);
        if (source.isSystemAccount()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        UserAccount target = userAccountRepository.findById(body.targetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (target.isSystemAccount()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (source.getId().equals(target.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (!body.migrateSettings() && !body.migrateGetters() && !body.migrateBindings()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        adminMigrationService.migrate(source, target,
                new AdminMigrationService.MigrateOptions(body.migrateSettings(), body.migrateGetters(), body.migrateBindings()));
    }

    /**
     * Marque un UserAccount régulier comme compte système (le bot pour les
     * commandes chat). Tout précédent système account est démarqué ; s'il est
     * vide (pas de Twitch ID, créé par SystemAccountInitializer comme template
     * de settings), il est supprimé pour éviter l'orphelin.
     */
    @PostMapping("/{id}/promote-to-system")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @org.springframework.transaction.annotation.Transactional
    public void promoteToSystem(@PathVariable UUID id) {
        UserAccount target = findOrThrow(id);
        if (target.isSystemAccount()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Already system account");
        }
        if (target.getTwitchId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target has no Twitch identity");
        }
        userAccountRepository.findBySystemAccountTrue().ifPresent(previous -> {
            previous.setSystemAccount(false);
            if (previous.getTwitchId() == null) {
                userAccountRepository.delete(previous);
                log.info("Deleted empty system-account placeholder {}", previous.getId());
            } else {
                userAccountRepository.save(previous);
                log.info("Demoted previous system account {} (still has Twitch id, kept as regular)", previous.getId());
            }
        });
        target.setSystemAccount(true);
        userAccountRepository.save(target);
        log.info("Promoted account {} (twitchId={}) as system account", target.getId(), target.getTwitchId());
    }

    @GetMapping("/{id}")
    public MemberSummary getMember(@PathVariable UUID id) {
        UserAccount user = findOrThrow(id);
        return new MemberSummary(user.getId(), user.getTwitchUsername());
    }

    private UserAccount findOrThrow(UUID id) {
        return userAccountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public record MembersPageData(List<UserAccount> members, Map<UUID, Boolean> liveStatus, boolean isMockProfile) {}

    public record MemberSummary(UUID id, String twitchUsername) {}

    public record MigrateRequest(UUID targetId, boolean migrateSettings, boolean migrateGetters, boolean migrateBindings) {}
}
