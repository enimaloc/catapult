package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.AddToGroupRequest;
import fr.enimaloc.catapult.common.dto.admin.AdminMemberDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMembersPage;
import fr.enimaloc.catapult.common.dto.admin.MemberTargetingDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMemberSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.MemberDebugStateDto;
import fr.enimaloc.catapult.common.dto.admin.MigrateRequest;
import fr.enimaloc.catapult.common.dto.admin.SetFlagRequest;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserFlag;
import fr.enimaloc.catapult.domain.account.UserGroup;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserFlagRepository;
import fr.enimaloc.catapult.repository.account.UserGroupRepository;
import fr.enimaloc.catapult.service.account.AccountService;
import fr.enimaloc.catapult.service.account.AdminMigrationService;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.debug.MemberDebugService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminMembersController {

    private final UserAccountRepository userAccountRepository;
    private final StreamStateService streamStateService;
    private final AccountService accountService;
    private final BotToggleService botToggleService;
    private final AdminMigrationService adminMigrationService;
    private final Environment environment;
    private final UserFlagRepository userFlagRepository;
    private final UserGroupRepository userGroupRepository;
    private final MemberDebugService memberDebugService;

    /** Members per page of the admin listing. */
    static final int PAGE_SIZE = 50;
    /** Most matches the notification picker gets. */
    static final int SEARCH_LIMIT = 20;

    /**
     * One page of members as catapult-web's admin page reads them (not the entity: no tokens, no
     * proxies), filtered by {@code q} when it isn't blank. Live status is asked for this page only.
     */
    @GetMapping
    public AdminMembersPage page(@RequestParam(defaultValue = "0") int page,
                                 @RequestParam(required = false) String q) {
        PageRequest request = PageRequest.of(Math.max(page, 0), PAGE_SIZE);
        String query = q == null ? "" : q.trim();
        Page<UserAccount> found = query.isEmpty()
                ? userAccountRepository.findMembersPage(request)
                : userAccountRepository.searchMembersPage(UserAccountRepository.likePattern(query), query, request);
        Map<UUID, Boolean> liveStatus = found.getContent().stream()
                .collect(Collectors.toMap(UserAccount::getId, streamStateService::isLive));
        boolean isMockProfile = Arrays.asList(environment.getActiveProfiles()).contains("mock");
        Map<UUID, MemberDebugStateDto> debug = memberDebugService.stateDtos(
                found.getContent().stream().map(UserAccount::getId).toList());
        return new AdminMembersPage(found.getContent().stream().map(u -> toDto(u, debug.get(u.getId()))).toList(),
                liveStatus, isMockProfile, found.getNumber(), found.getTotalPages(), found.getTotalElements());
    }

    /** At most 20 members matching {@code q} like the listing does, for a picker; no live status. */
    @GetMapping("/search")
    public List<AdminMemberDto> search(@RequestParam(required = false) String q) {
        String query = q == null ? "" : q.trim();
        if (query.isEmpty()) {
            return List.of();
        }
        List<UserAccount> found = userAccountRepository.searchMembers(UserAccountRepository.likePattern(query), query,
                PageRequest.of(0, SEARCH_LIMIT));
        Map<UUID, MemberDebugStateDto> debug = memberDebugService.stateDtos(
                found.stream().map(UserAccount::getId).toList());
        return found.stream().map(u -> toDto(u, debug.get(u.getId()))).toList();
    }

    static AdminMemberDto toDto(UserAccount user, MemberDebugStateDto debug) {
        return new AdminMemberDto(user.getId(), user.getTwitchId(), user.getTwitchUsername(), user.getProfileImageUrl(),
                user.getSteamId(), user.isSystemAccount(), user.isBotEnabled(),
                user.getStatus() == null ? null : user.getStatus().name(), user.getCreatedAt(), debug);
    }

    @PostMapping("/{id}/bot/toggle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleBot(@PathVariable UUID id) {
        UserAccount user = findOrThrow(id);
        botToggleService.setBotEnabled(user, !user.isBotEnabled());
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
     * commandes chat). Tout précédent système account est démarqué ; s'il n'a
     * pas de Twitch identity, il est supprimé pour éviter l'orphelin.
     */
    @PostMapping("/{id}/promote-to-system")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
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
    public AdminMemberSummaryDto getMember(@PathVariable UUID id) {
        UserAccount user = findOrThrow(id);
        return new AdminMemberSummaryDto(user.getId(), user.getTwitchUsername());
    }

    @PostMapping("/{id}/flags")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setFlag(@PathVariable UUID id, @RequestBody SetFlagRequest body) {
        UserAccount user = findOrThrow(id);
        if (body.key() == null || body.key().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing key");
        }
        UserFlag flag = userFlagRepository.findByUserAndFlagKey(user, body.key().trim())
                .orElseGet(() -> {
                    UserFlag f = new UserFlag();
                    f.setUser(user);
                    f.setFlagKey(body.key().trim());
                    return f;
                });
        flag.setFlagValue(body.value());
        userFlagRepository.save(flag);
    }

    @PostMapping("/{id}/flags/{key}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFlag(@PathVariable UUID id, @PathVariable String key) {
        UserAccount user = findOrThrow(id);
        userFlagRepository.findByUserAndFlagKey(user, key).ifPresent(userFlagRepository::delete);
    }

    @PostMapping("/{id}/groups")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addToGroup(@PathVariable UUID id, @RequestBody AddToGroupRequest body) {
        UserAccount user = findOrThrow(id);
        UserGroup group = userGroupRepository.findByKey(body.groupKey())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        group.getMembers().add(user);
        userGroupRepository.save(group);
    }

    @PostMapping("/{id}/groups/{groupId}/delete")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFromGroup(@PathVariable UUID id, @PathVariable UUID groupId) {
        UserGroup group = userGroupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        group.getMembers().removeIf(m -> m.getId().equals(id));
        userGroupRepository.save(group);
    }

    @GetMapping("/{id}/targeting")
    @Transactional(readOnly = true)
    public MemberTargetingDto targeting(@PathVariable UUID id) {
        UserAccount user = findOrThrow(id);
        List<MemberTargetingDto.Flag> flags = userFlagRepository.findByUser(user).stream()
                .map(f -> new MemberTargetingDto.Flag(f.getFlagKey(), f.getFlagValue())).toList();
        List<String> groups = userGroupRepository.findAll().stream()
                .filter(g -> g.getMembers().stream().anyMatch(m -> m.getId().equals(id)))
                .map(UserGroup::getKey).toList();
        return new MemberTargetingDto(flags, groups);
    }

    private UserAccount findOrThrow(UUID id) {
        return userAccountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
