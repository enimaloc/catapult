package fr.enimaloc.catapult.service.battlenet;

import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.repository.battlenet.BattleNetServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.battlenet.login.BattleNetLoginCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * An admin's pool of Battle.net bot accounts (spec §10.3): enrolment through the login coordinator, 2FA code,
 * re-auth, edits and removal. Whenever an interactive login ends {@code READY}, the account's BGS session is
 * (re)started.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("battlenet.enabled")
public class BattleNetServiceAccountAdmin {

    /**
     * What the admin UI sees of a bot account: never the e-mail, the password or the browser state.
     * {@code waitingForCode}: an admin login waits for its 2FA code, so {@link #submitCode} can take one.
     */
    public record AccountDto(UUID id, String label, String battleTag, String authState, String sessionState,
                             int fillOrder, boolean friendLimitReached, boolean enabled, long friendCount,
                             boolean waitingForCode) {}

    private final BattleNetServiceAccountRepository accountRepository;
    private final BattleNetFriendLinkRepository linkRepository;
    private final TokenEncryptionService encryption;
    private final BattleNetLoginCoordinator loginCoordinator;
    private final BattleNetSessionManager sessionManager;

    public List<AccountDto> list() {
        return accountRepository.findAll().stream().map(this::toDto).toList();
    }

    /** Stores the account with encrypted credentials and starts its interactive login in the background. */
    public AccountDto create(String label, String email, String password) {
        requireText(label, "label");
        requireText(email, "email");
        requireText(password, "password");
        BattleNetServiceAccount account = new BattleNetServiceAccount();
        account.setLabel(label);
        account.setEmail(email);
        account.setPasswordEnc(encryption.encrypt(password));
        account.setAuthState(AuthState.NEW);
        account.setFillOrder((int) accountRepository.count());
        account.setUpdatedAt(Instant.now());
        BattleNetServiceAccount saved = accountRepository.save(account);
        log.info("Battle.net service account created: {}", saved.getLabel());
        if (!startLogin(saved.getId())) {
            // the account is stored: the admin can re-authenticate it, so a refused start is not a failed create
            saved.setAuthState(AuthState.AUTH_FAILED);
            saved = accountRepository.save(saved);
        }
        return toDto(saved);
    }

    /** Feeds the 2FA code to the login waiting for it: 404 for an unknown account, 409 when none waits. */
    public void submitCode(UUID id, String code) {
        findOrThrow(id);
        requireText(code, "code");
        try {
            loginCoordinator.submitCode(id, code);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No login is waiting for a code");
        }
    }

    /** Runs the interactive login again, for an account whose session needs an admin. */
    public AccountDto reauth(UUID id) {
        BattleNetServiceAccount account = findOrThrow(id);
        if (!startLogin(id)) throw loginInProgress();
        return toDto(account);
    }

    /**
     * 404 for an unknown account before 400 for a field of the wrong type. A password change while a login runs
     * is refused (409) before anything is saved.
     */
    public AccountDto update(UUID id, Map<String, Object> body) {
        findOrThrow(id);
        boolean passwordChanged = body.containsKey("password");
        if (passwordChanged && loginCoordinator.loginInProgress(id)) throw loginInProgress();
        boolean[] enabledChanged = new boolean[1];
        BattleNetServiceAccount saved = withRetry(() -> {
            BattleNetServiceAccount account = findOrThrow(id);
            enabledChanged[0] = false;
            if (body.containsKey("label")) {
                account.setLabel(requireText(field(body, "label", String.class), "label"));
            }
            if (body.containsKey("enabled")) {
                boolean enabled = field(body, "enabled", Boolean.class);
                enabledChanged[0] = enabled != account.isEnabled();
                account.setEnabled(enabled);
            }
            if (body.containsKey("friendLimitReached")) {
                account.setFriendLimitReached(field(body, "friendLimitReached", Boolean.class));
            }
            if (body.containsKey("fillOrder")) {
                account.setFillOrder(field(body, "fillOrder", Number.class).intValue());
            }
            if (passwordChanged) {
                account.setPasswordEnc(
                        encryption.encrypt(requireText(field(body, "password", String.class), "password")));
                account.setAuthState(AuthState.NEW);
            }
            account.setUpdatedAt(Instant.now());
            return accountRepository.save(account);
        });
        if (passwordChanged) {
            // the bot must authenticate again with the new password
            sessionManager.stop(id);
            if (!startLogin(id)) {
                log.warn("Battle.net service account {}: the login after a password change could not start", id);
            }
        } else if (enabledChanged[0]) {
            sessionManager.restart(id);
        }
        return toDto(saved);
    }

    /** Stops the session first, then drops the links and the account. */
    public void delete(UUID id) {
        findOrThrow(id);
        sessionManager.stop(id);
        BattleNetServiceAccount deleted = withRetry(() -> {
            BattleNetServiceAccount account = findOrThrow(id);
            linkRepository.deleteAll(linkRepository.findByServiceAccount(account));
            accountRepository.delete(account);
            return account;
        });
        log.info("Battle.net service account deleted: {}", deleted.getLabel());
    }

    /**
     * Runs a read-modify-write of the account again once when a login committed in between (optimistic lock),
     * 409 when it still conflicts.
     */
    private <T> T withRetry(Supplier<T> write) {
        try {
            return write.get();
        } catch (ObjectOptimisticLockingFailureException first) {
            try {
                return write.get();
            } catch (ObjectOptimisticLockingFailureException second) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The account was modified concurrently");
            }
        }
    }

    private static ResponseStatusException loginInProgress() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "A login is already in progress");
    }

    /** False when the login could not start (one is already in progress); true once it runs in the background. */
    private boolean startLogin(UUID id) {
        CompletableFuture<Void> login = loginCoordinator.startInteractive(id);
        if (login.isCompletedExceptionally()) {
            return false;
        }
        login.whenComplete((ignored, failure) -> {
            if (failure == null) {
                sessionManager.restart(id);
            } else {
                log.warn("Battle.net login of service account {} did not complete ({})", id,
                        failure.getClass().getSimpleName());
            }
        });
        return true;
    }

    private BattleNetServiceAccount findOrThrow(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown account"));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " is required");
        }
        return value;
    }

    /** A PATCH field of the expected JSON type, 400 otherwise. */
    private static <T> T field(Map<String, Object> body, String name, Class<T> type) {
        Object value = body.get(name);
        if (!type.isInstance(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid type for " + name);
        }
        return type.cast(value);
    }

    /** The friend count is the ACCEPTED links in the database, so it needs no live session. */
    private AccountDto toDto(BattleNetServiceAccount account) {
        return new AccountDto(account.getId(), account.getLabel(), account.getBattleTag(),
                account.getAuthState().name(), sessionManager.sessionState(account.getId()),
                account.getFillOrder(), account.isFriendLimitReached(), account.isEnabled(),
                linkRepository.countByServiceAccountAndStatus(account, BattleNetFriendLink.Status.ACCEPTED),
                loginCoordinator.waitingForCode(account.getId()));
    }
}
