package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.dryrun.MemberDryRun;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.dryrun.MemberDryRunRepository;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A member's dry run: an admin switches it on, the admin or the member switches it off. While it is on, Catapult
 * writes nothing public on the member's Twitch channel (see {@link DryRunRegistry#isActive}).
 */
@Slf4j
@Service
public class DryRunService {

    static final String TURNED_ON = "Dry run turned on by an administrator";
    static final String TURNED_OFF = "Dry run turned off";

    private final MemberDryRunRepository dryRuns;
    private final UserAccountRepository users;
    private final DryRunRegistry registry;
    private final GameStateService gameStateService;
    private final ActivityLogService activityLogService;
    private final Clock clock;

    @Autowired
    public DryRunService(MemberDryRunRepository dryRuns, UserAccountRepository users, DryRunRegistry registry,
                         GameStateService gameStateService, ActivityLogService activityLogService) {
        this(dryRuns, users, registry, gameStateService, activityLogService, Clock.systemUTC());
    }

    DryRunService(MemberDryRunRepository dryRuns, UserAccountRepository users, DryRunRegistry registry,
                  GameStateService gameStateService, ActivityLogService activityLogService, Clock clock) {
        this.dryRuns = dryRuns;
        this.users = users;
        this.registry = registry;
        this.gameStateService = gameStateService;
        this.activityLogService = activityLogService;
        this.clock = clock;
    }

    /** Puts {@code userId} in dry run on {@code adminId}'s behalf. 404 for an unknown member, 409 when already on. */
    @Transactional
    public MemberDryRun enable(UUID adminId, UUID userId) {
        requireMember(userId);
        if (dryRuns.findById(userId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dry run already active");
        }
        MemberDryRun row = new MemberDryRun();
        row.setUserId(userId);
        row.setEnabledBy(adminId);
        row.setEnabledAt(Instant.now(clock));
        MemberDryRun saved = dryRuns.save(row);
        afterCommit(() -> {
            registry.activate(userId);
            activityLogService.addEntry(userId, "INFO", TURNED_ON);
            log.info("Dry run of {} turned on by {}", userId, adminId);
        });
        return saved;
    }

    /**
     * Takes {@code userId} out of dry run, {@code byUserId} (the member or an admin, null when unknown) asking.
     * The member's remembered game state is forgotten so the next detection cycle applies what it should.
     * 404 for an unknown member; no-op when not in dry run.
     */
    @Transactional
    public void disable(UUID byUserId, UUID userId) {
        UserAccount user = requireMember(userId);
        dryRuns.findById(userId).ifPresent(row -> {
            dryRuns.delete(row);
            afterCommit(() -> {
                registry.deactivate(userId);
                gameStateService.clearState(user);
                activityLogService.addEntry(userId, "INFO", TURNED_OFF);
                log.info("Dry run of {} turned off by {}", userId, byUserId);
            });
        });
    }

    public Optional<MemberDryRun> state(UUID userId) {
        return dryRuns.findById(userId);
    }

    /** Those of {@code userIds} in dry run, in one query (none when {@code userIds} is empty). */
    public Set<UUID> activeAmong(Collection<UUID> userIds) {
        Set<UUID> result = new HashSet<>();
        if (userIds.isEmpty()) {
            return result;
        }
        dryRuns.findAllById(userIds).forEach(row -> result.add(row.getUserId()));
        return result;
    }

    private UserAccount requireMember(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Applies the in-memory changes once the DB change is committed, so a rollback never leaves them disagreeing. */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
