package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.common.dto.admin.AdminMemberDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMemberSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMembersPage;
import fr.enimaloc.catapult.common.dto.admin.FlagKeyDto;
import fr.enimaloc.catapult.common.dto.admin.MemberDebugStateDto;
import fr.enimaloc.catapult.common.dto.admin.MemberTargetingDto;
import fr.enimaloc.catapult.common.dto.admin.MigrateRequest;
import fr.enimaloc.catapult.common.dto.admin.SetFlagRequest;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserFlag;
import fr.enimaloc.catapult.domain.account.UserGroup;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserFlagRepository;
import fr.enimaloc.catapult.repository.account.UserGroupRepository;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.debug.MemberDebugService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** The admin's work on members: listing, deletion, links, migration, system account, flags and groups. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMemberService {

    /** Members per page of the admin listing. */
    static final int PAGE_SIZE = 50;
    /** Most matches the notification picker gets. */
    static final int SEARCH_LIMIT = 20;

    private final UserAccountRepository userAccountRepository;
    private final StreamStateService streamStateService;
    private final AccountService accountService;
    private final BotToggleService botToggleService;
    private final AdminMigrationService adminMigrationService;
    private final Environment environment;
    private final UserFlagRepository userFlagRepository;
    private final UserGroupRepository userGroupRepository;
    private final MemberDebugService memberDebugService;

    /**
     * One page of members as catapult-web's admin page reads them (not the entity: no tokens, no
     * proxies), filtered by {@code q} when it isn't blank. Live status is asked for this page only.
     */
    public AdminMembersPage page(int page, String q) {
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
    public List<AdminMemberDto> search(String q) {
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

    private static AdminMemberDto toDto(UserAccount user, MemberDebugStateDto debug) {
        return new AdminMemberDto(user.getId(), user.getTwitchId(), user.getTwitchUsername(), user.getProfileImageUrl(),
                user.getSteamId(), user.isSystemAccount(), user.isBotEnabled(),
                user.getStatus() == null ? null : user.getStatus().name(), user.getCreatedAt(), debug);
    }

    public void toggleBot(UUID id) {
        UserAccount user = findOrThrow(id);
        botToggleService.setBotEnabled(user, !user.isBotEnabled());
    }

    /** The caller's Twitch id is only read once the account is known and not the system one. */
    public void deleteAccount(UUID id, Supplier<String> callerTwitchId) {
        UserAccount user = findOrThrow(id);
        if (user.isSystemAccount()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String caller = callerTwitchId.get();
        if (user.getTwitchId().equals(caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        log.info("Admin {} deleted account {} (twitchId={})", caller, user.getId(), user.getTwitchId());
        accountService.deleteAccountImmediately(user);
    }

    public void unlinkSteam(UUID id) {
        UserAccount user = findOrThrow(id);
        if (user.getSteamId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        accountService.disconnectProvider(user, OAuthToken.Provider.STEAM);
    }

    public void unlinkTwitch(UUID id) {
        UserAccount user = findOrThrow(id);
        if (user.getStatus() != UserAccount.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        accountService.unlinkTwitch(user);
    }

    /** An unknown source is a 404 before any check of the request. */
    public void migrate(UUID id, MigrateRequest body) {
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
     * Marks a regular UserAccount as the system account (the bot for chat
     * commands). Any previous system account is unmarked; if it has no
     * Twitch identity, it is deleted to avoid leaving an orphan.
     */
    @Transactional
    public void promoteToSystem(UUID id) {
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

    public AdminMemberSummaryDto summary(UUID id) {
        UserAccount user = findOrThrow(id);
        return new AdminMemberSummaryDto(user.getId(), user.getTwitchUsername());
    }

    /** An unknown member is a 404 before the missing key's 400. */
    public void setFlag(UUID id, SetFlagRequest body) {
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

    public void deleteFlag(UUID id, String key) {
        UserAccount user = findOrThrow(id);
        userFlagRepository.findByUserAndFlagKey(user, key).ifPresent(userFlagRepository::delete);
    }

    @Transactional
    public void addToGroup(UUID id, String groupKey) {
        UserAccount user = findOrThrow(id);
        UserGroup group = userGroupRepository.findByKey(groupKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        group.getMembers().add(user);
        userGroupRepository.save(group);
    }

    @Transactional
    public void removeFromGroup(UUID id, UUID groupId) {
        UserGroup group = userGroupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        group.getMembers().removeIf(m -> m.getId().equals(id));
        userGroupRepository.save(group);
    }

    @Transactional(readOnly = true)
    public MemberTargetingDto targeting(UUID id) {
        UserAccount user = findOrThrow(id);
        List<MemberTargetingDto.Flag> flags = userFlagRepository.findByUser(user).stream()
                .map(f -> new MemberTargetingDto.Flag(f.getFlagKey(), f.getFlagValue())).toList();
        List<String> groups = userGroupRepository.findAll().stream()
                .filter(g -> g.getMembers().stream().anyMatch(m -> m.getId().equals(id)))
                .map(UserGroup::getKey).toList();
        return new MemberTargetingDto(flags, groups);
    }

    /** The targeting tags members carry, for the admin page's override form to propose. */
    @Transactional(readOnly = true)
    public List<FlagKeyDto> flagKeys() {
        // rows arrive ordered by key then value, so a linked map keeps both orders
        Map<String, List<String>> valuesByKey = new LinkedHashMap<>();
        for (Object[] row : userFlagRepository.findDistinctKeyValues()) {
            List<String> values = valuesByKey.computeIfAbsent((String) row[0], k -> new ArrayList<>());
            if (row[1] != null) values.add((String) row[1]);
        }
        return valuesByKey.entrySet().stream().map(e -> new FlagKeyDto(e.getKey(), e.getValue())).toList();
    }

    private UserAccount findOrThrow(UUID id) {
        return userAccountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
