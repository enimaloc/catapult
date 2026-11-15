package fr.enimaloc.catapult.service.epic;

import fr.enimaloc.catapult.domain.epic.EpicServiceAccount;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.epic.EpicServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.epic.api.EpicAccountClient;
import fr.enimaloc.catapult.service.epic.api.EpicAccountClient.Tokens;
import fr.enimaloc.catapult.service.epic.api.EpicAuthException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The access tokens of the Epic bots (spec §4.1). The only component that refreshes a bot: Epic rotates the refresh
 * token on every refresh, so two refreshes with the same token, or a new token lost before it is stored, would lose
 * the bot. Hence one refresh at a time per bot, and the rotated refresh token committed in its own transaction
 * before the access token is handed out.
 *
 * <p>Access tokens live in memory only; a token is reused while it has more than {@link #MIN_REMAINING} left.
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty("epic.enabled")
public class EpicTokenService {

    /** A cached access token is refreshed once it has less than this left. */
    public static final Duration MIN_REMAINING = Duration.ofMinutes(10);
    /** Writes of the rotated token lost to a concurrent edit of the account (optimistic lock) are retried. */
    static final int SAVE_ATTEMPTS = 3;

    /** An access token and when it expires. Its {@code toString} never shows the token. */
    public record AccessToken(String value, Instant expiresAt) {
        @Override
        public String toString() {
            return "AccessToken[expiresAt=" + expiresAt + "]";
        }
    }

    private final EpicServiceAccountRepository repository;
    private final EpicAccountClient accounts;
    private final TokenEncryptionService encryption;
    private final TransactionOperations ownTransaction;
    private final Clock clock;

    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();
    private final Map<UUID, AccessToken> cache = new ConcurrentHashMap<>();
    /** Refresh tokens Epic rotated that could not be stored yet: never logged, retried before any refresh. */
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    @Autowired
    public EpicTokenService(EpicServiceAccountRepository repository,
                            EpicAccountClient accounts,
                            TokenEncryptionService encryption,
                            PlatformTransactionManager transactionManager) {
        this(repository, accounts, encryption, requiresNew(transactionManager), Clock.systemUTC());
    }

    /** @param ownTransaction runs the writes in a transaction of their own ({@code REQUIRES_NEW}) */
    EpicTokenService(EpicServiceAccountRepository repository,
                     EpicAccountClient accounts,
                     TokenEncryptionService encryption,
                     TransactionOperations ownTransaction,
                     Clock clock) {
        this.repository = repository;
        this.accounts = accounts;
        this.encryption = encryption;
        this.ownTransaction = ownTransaction;
        this.clock = clock;
    }

    private static TransactionOperations requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /**
     * An access token of the bot with more than {@link #MIN_REMAINING} left, refreshed when needed.
     *
     * @throws EpicAuthException      ({@code REFRESH_REFUSED}) when Epic refuses the refresh token; the bot is then
     *                                {@code REAUTH_REQUIRED}
     * @throws IllegalStateException  when the bot does not exist
     * @throws RuntimeException       when Epic or the database fails; no token is handed out then
     */
    public String accessToken(UUID botId) {
        return token(botId).value();
    }

    /** Like {@link #accessToken}, with the token's expiry. */
    public AccessToken token(UUID botId) {
        synchronized (lockOf(botId)) {
            storePending(botId);
            AccessToken cached = cache.get(botId);
            if (cached != null && cached.expiresAt().isAfter(clock.instant().plus(MIN_REMAINING))) {
                return cached;
            }
            cache.remove(botId);
            return refresh(botId);
        }
    }

    /** Forgets the bot's access token (Epic rejected it): the next call refreshes. */
    public void evict(UUID botId) {
        cache.remove(botId);
    }

    /**
     * Stores the tokens of a fresh login of an existing bot (authorization code exchange): the refresh token, its
     * expiry, {@code lastRefreshAt} and {@code READY}, in a transaction of its own. Taken under the bot's lock, so a
     * refresh in flight can neither overwrite the new login nor mark it {@code REAUTH_REQUIRED}; a rotated token still
     * waiting to be stored is dropped once the login is stored, the login replaces it.
     *
     * @throws IllegalStateException when the bot does not exist
     * @throws RuntimeException      when the database fails; nothing is cached and a waiting rotated token is kept
     */
    public void install(UUID botId, Tokens tokens) {
        synchronized (lockOf(botId)) {
            cache.remove(botId);
            String refreshTokenEnc = encryption.encrypt(tokens.refreshToken());
            Instant now = clock.instant();
            write(botId, account -> {
                account.setRefreshTokenEnc(refreshTokenEnc);
                account.setRefreshExpiresAt(tokens.refreshExpiresAt());
                account.setLastRefreshAt(now);
                account.setAuthState(AuthState.READY);
            });
            pending.remove(botId);
            cache.put(botId, new AccessToken(tokens.accessToken(), tokens.accessExpiresAt()));
            log.info("Stored a new login of Epic service account {}", botId);
        }
    }

    /** Drops everything held in memory for the bot (its access token, a rotated token waiting): it is being deleted. */
    public void forget(UUID botId) {
        synchronized (lockOf(botId)) {
            cache.remove(botId);
            pending.remove(botId);
        }
    }

    private Object lockOf(UUID botId) {
        return locks.computeIfAbsent(botId, id -> new Object());
    }

    /**
     * Called with the bot's lock held. Retries storing a refresh token Epic rotated but a previous call could not
     * store: the stored one is already consumed, refreshing with it would lose the bot.
     *
     * @throws RuntimeException when it still cannot be stored; no token is handed out then
     */
    private void storePending(UUID botId) {
        Pending waiting = pending.get(botId);
        if (waiting == null) {
            return;
        }
        store(botId, waiting.tokens(), waiting.receivedAt());
        pending.remove(botId);
        cache.put(botId, new AccessToken(waiting.tokens().accessToken(), waiting.tokens().accessExpiresAt()));
        log.info("Stored the rotated refresh token of Epic service account {} on a retry", botId);
    }

    /** Called with the bot's lock held. */
    private AccessToken refresh(UUID botId) {
        EpicServiceAccount account = repository.findById(botId)
                .orElseThrow(() -> new IllegalStateException("Unknown Epic service account " + botId));
        if (account.getAuthState() != AuthState.READY) {
            throw new EpicAuthException(EpicAuthException.Reason.REFRESH_REFUSED);
        }
        Tokens tokens;
        try {
            tokens = accounts.refresh(encryption.decrypt(account.getRefreshTokenEnc()));
        } catch (EpicAuthException e) {
            if (e.reason() == EpicAuthException.Reason.REFRESH_REFUSED) {
                log.warn("Epic refused the refresh token of service account {}: it needs a new login", botId);
                markReauthRequired(botId);
            }
            throw e;
        }
        // Epic consumed the stored token: until the new one is stored, it only lives here
        Instant now = clock.instant();
        pending.put(botId, new Pending(tokens, now));
        store(botId, tokens, now);
        pending.remove(botId);
        AccessToken token = new AccessToken(tokens.accessToken(), tokens.accessExpiresAt());
        cache.put(botId, token);
        log.info("Refreshed the access token of Epic service account {}", botId);
        return token;
    }

    /** Commits the rotated refresh token; throws, and so hands out no access token, when it cannot. */
    private void store(UUID botId, Tokens tokens, Instant receivedAt) {
        String refreshTokenEnc = encryption.encrypt(tokens.refreshToken());
        try {
            write(botId, account -> {
                account.setRefreshTokenEnc(refreshTokenEnc);
                account.setRefreshExpiresAt(tokens.refreshExpiresAt());
                account.setLastRefreshAt(receivedAt);
            });
        } catch (RuntimeException e) {
            log.error("Could not store the rotated refresh token of Epic service account {}, kept in memory to "
                    + "retry on the next call ({})", botId, e.getClass().getName());
            throw e;
        }
    }

    /** Applies {@code change} to the bot in a transaction of its own, retrying a write lost to a concurrent edit. */
    private void write(UUID botId, Consumer<EpicServiceAccount> change) {
        for (int attempt = 1; ; attempt++) {
            try {
                ownTransaction.executeWithoutResult(status -> {
                    EpicServiceAccount stored = repository.findById(botId).orElseThrow(
                            () -> new IllegalStateException("Unknown Epic service account " + botId));
                    change.accept(stored);
                    repository.save(stored);
                });
                return;
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= SAVE_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private void markReauthRequired(UUID botId) {
        try {
            ownTransaction.executeWithoutResult(status -> repository.findById(botId).ifPresent(account -> {
                account.setAuthState(AuthState.REAUTH_REQUIRED);
                repository.save(account);
            }));
        } catch (RuntimeException e) {
            log.error("Could not set Epic service account {} to REAUTH_REQUIRED: {}", botId, e.toString());
        }
    }

    /** Tokens Epic returned whose refresh token is not stored yet. Its {@code toString} never shows a token. */
    private record Pending(Tokens tokens, Instant receivedAt) {
        @Override
        public String toString() {
            return "Pending[receivedAt=" + receivedAt + "]";
        }
    }
}
