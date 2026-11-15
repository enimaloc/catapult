package fr.enimaloc.catapult.service.epic;

import fr.enimaloc.catapult.domain.epic.EpicServiceAccount;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.epic.EpicServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.epic.api.EpicAccountClient;
import fr.enimaloc.catapult.service.epic.api.EpicAccountClient.Tokens;
import fr.enimaloc.catapult.service.epic.api.EpicAuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpicTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T07:00:00Z");

    private final EpicServiceAccountRepository repository = mock(EpicServiceAccountRepository.class);
    private final EpicAccountClient accounts = mock(EpicAccountClient.class);
    private final TokenEncryptionService encryption = new TokenEncryptionService("0123456789abcdef0123456789abcdef");
    private final MutableClock clock = new MutableClock(NOW);
    /** Records that the writes ran in their own transaction. */
    private final AtomicBoolean inOwnTransaction = new AtomicBoolean();
    private final TransactionOperations ownTransaction = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            inOwnTransaction.set(true);
            try {
                return TransactionOperations.withoutTransaction().execute(action);
            } finally {
                inOwnTransaction.set(false);
            }
        }
    };

    private EpicServiceAccount bot;
    private EpicTokenService service;

    @BeforeEach
    void setUp() {
        bot = new EpicServiceAccount();
        bot.setId(UUID.randomUUID());
        bot.setEpicAccountId("27871f9dd56446c8a1f1d3db1aaa8a7e");
        bot.setDisplayName("Catapult-1");
        bot.setRefreshTokenEnc(encryption.encrypt("refresh-1"));
        bot.setRefreshExpiresAt(NOW.plus(Duration.ofDays(300)));
        bot.setLastRefreshAt(NOW.minus(Duration.ofDays(1)));
        when(repository.findById(bot.getId())).thenReturn(Optional.of(bot));
        when(repository.save(any())).thenAnswer(invocation -> {
            assertThat(inOwnTransaction).as("saved in its own transaction").isTrue();
            return invocation.getArgument(0);
        });
        service = new EpicTokenService(repository, accounts, encryption, ownTransaction, clock);
    }

    private static Tokens tokens(String access, Instant accessExpiresAt, String refresh) {
        return new Tokens(access, accessExpiresAt, refresh, NOW.plus(Duration.ofDays(365)),
                "27871f9dd56446c8a1f1d3db1aaa8a7e", "Catapult-1");
    }

    @Test
    void refresh_persistsTheRotatedTokenBeforeReturningTheAccessToken() {
        when(accounts.refresh("refresh-1")).thenReturn(tokens("access-1", NOW.plus(Duration.ofHours(2)), "refresh-2"));

        assertThat(service.accessToken(bot.getId())).isEqualTo("access-1");

        InOrder order = inOrder(accounts, repository);
        order.verify(accounts).refresh("refresh-1");
        order.verify(repository).save(bot);
        assertThat(encryption.decrypt(bot.getRefreshTokenEnc())).isEqualTo("refresh-2");
        assertThat(bot.getRefreshExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(365)));
        assertThat(bot.getLastRefreshAt()).isEqualTo(NOW);
    }

    @Test
    void refresh_whoseSaveFails_handsOutNoAccessToken_andKeepsTheRotatedToken() {
        when(accounts.refresh("refresh-1")).thenReturn(tokens("access-1", NOW.plus(Duration.ofHours(2)), "refresh-2"));
        when(accounts.refresh("refresh-2")).thenReturn(tokens("access-2", NOW.plus(Duration.ofHours(4)), "refresh-3"));
        doThrow(new DataAccessResourceFailureException("database down"))
                .doThrow(new DataAccessResourceFailureException("database still down"))
                .doAnswer(invocation -> invocation.getArgument(0)).when(repository).save(any());

        assertThatThrownBy(() -> service.accessToken(bot.getId()))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageNotContaining("access-1");

        // The consumed token is never sent again: the rotated one is stored first, still no access token without it
        assertThatThrownBy(() -> service.accessToken(bot.getId()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(accounts, times(1)).refresh(anyString());

        // Once stored, the access token of that same reply is handed out, without a new rotation
        assertThat(service.accessToken(bot.getId())).isEqualTo("access-1");
        assertThat(encryption.decrypt(bot.getRefreshTokenEnc())).isEqualTo("refresh-2");
        verify(accounts, times(1)).refresh(anyString());

        // The next refresh starts from the rotated token
        clock.set(NOW.plus(Duration.ofMinutes(115)));
        assertThat(service.accessToken(bot.getId())).isEqualTo("access-2");
        verify(accounts).refresh("refresh-2");
        verify(accounts, never()).refresh("refresh-3");
        assertThat(encryption.decrypt(bot.getRefreshTokenEnc())).isEqualTo("refresh-3");
    }

    @Test
    void install_storesALogin_replacesAPendingToken_andIsCached() {
        when(accounts.refresh("refresh-1")).thenReturn(tokens("access-1", NOW.plus(Duration.ofHours(2)), "refresh-2"));
        doThrow(new DataAccessResourceFailureException("database down"))
                .doAnswer(invocation -> {
                    assertThat(inOwnTransaction).as("saved in its own transaction").isTrue();
                    return invocation.getArgument(0);
                }).when(repository).save(any());
        assertThatThrownBy(() -> service.accessToken(bot.getId()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        bot.setAuthState(AuthState.REAUTH_REQUIRED);
        clock.set(NOW.plus(Duration.ofMinutes(1)));

        service.install(bot.getId(), new Tokens("access-login", NOW.plus(Duration.ofHours(2)), "refresh-login",
                NOW.plus(Duration.ofDays(365)), bot.getEpicAccountId(), "Catapult-1"));

        assertThat(encryption.decrypt(bot.getRefreshTokenEnc())).isEqualTo("refresh-login");
        assertThat(bot.getAuthState()).isEqualTo(AuthState.READY);
        assertThat(bot.getRefreshExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(365)));
        assertThat(bot.getLastRefreshAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        // The pending rotated token of the old login is dropped, the login's access token is served from the cache
        assertThat(service.accessToken(bot.getId())).isEqualTo("access-login");
        assertThat(encryption.decrypt(bot.getRefreshTokenEnc())).isEqualTo("refresh-login");
        verify(accounts, times(1)).refresh(anyString());
    }

    @Test
    void install_waitsForARefreshInFlight() throws Exception {
        CountDownLatch inRefresh = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(accounts.refresh("refresh-1")).thenAnswer(invocation -> {
            inRefresh.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return tokens("access-1", NOW.plus(Duration.ofHours(2)), "refresh-2");
        });
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<String> refreshing = threads.submit(() -> service.accessToken(bot.getId()));
            assertThat(inRefresh.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> login = threads.submit(() -> service.install(bot.getId(), new Tokens("access-login",
                    NOW.plus(Duration.ofHours(2)), "refresh-login", NOW.plus(Duration.ofDays(365)),
                    bot.getEpicAccountId(), "Catapult-1")));
            release.countDown();
            assertThat(refreshing.get(5, TimeUnit.SECONDS)).isEqualTo("access-1");
            login.get(5, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
        }

        // The login, stored after the refresh, wins
        assertThat(encryption.decrypt(bot.getRefreshTokenEnc())).isEqualTo("refresh-login");
        assertThat(service.accessToken(bot.getId())).isEqualTo("access-login");
    }

    @Test
    void refresh_retriesTheSave_afterAConcurrentEditOfTheAccount() {
        when(accounts.refresh("refresh-1")).thenReturn(tokens("access-1", NOW.plus(Duration.ofHours(2)), "refresh-2"));
        doThrow(new OptimisticLockingFailureException("edited"))
                .doAnswer(invocation -> invocation.getArgument(0)).when(repository).save(any());

        assertThat(service.accessToken(bot.getId())).isEqualTo("access-1");
        verify(repository, times(2)).save(bot);
        assertThat(encryption.decrypt(bot.getRefreshTokenEnc())).isEqualTo("refresh-2");
    }

    @Test
    void concurrentCalls_refreshOnce() throws Exception {
        CountDownLatch inRefresh = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(accounts.refresh("refresh-1")).thenAnswer(invocation -> {
            inRefresh.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return tokens("access-1", NOW.plus(Duration.ofHours(2)), "refresh-2");
        });
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = threads.submit(() -> service.accessToken(bot.getId()));
            assertThat(inRefresh.await(5, TimeUnit.SECONDS)).isTrue();
            Future<String> second = threads.submit(() -> service.accessToken(bot.getId()));
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("access-1");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("access-1");
        } finally {
            threads.shutdownNow();
        }
        verify(accounts, times(1)).refresh(anyString());
    }

    @Test
    void cachedToken_isReusedUntil10MinutesBeforeExpiry() {
        when(accounts.refresh(anyString()))
                .thenReturn(tokens("access-1", NOW.plus(Duration.ofHours(2)), "refresh-2"))
                .thenReturn(tokens("access-2", NOW.plus(Duration.ofHours(4)), "refresh-3"));

        assertThat(service.accessToken(bot.getId())).isEqualTo("access-1");
        clock.set(NOW.plus(Duration.ofMinutes(109)));
        assertThat(service.accessToken(bot.getId())).isEqualTo("access-1");
        verify(accounts, times(1)).refresh(anyString());

        clock.set(NOW.plus(Duration.ofMinutes(110)));
        assertThat(service.token(bot.getId()))
                .isEqualTo(new EpicTokenService.AccessToken("access-2", NOW.plus(Duration.ofHours(4))));
        verify(accounts).refresh("refresh-2");
    }

    @Test
    void evictedToken_isRefreshed() {
        when(accounts.refresh(anyString()))
                .thenReturn(tokens("access-1", NOW.plus(Duration.ofHours(2)), "refresh-2"))
                .thenReturn(tokens("access-2", NOW.plus(Duration.ofHours(2)), "refresh-3"));
        service.accessToken(bot.getId());

        service.evict(bot.getId());

        assertThat(service.accessToken(bot.getId())).isEqualTo("access-2");
    }

    @Test
    void refusedRefresh_setsReauthRequired() {
        when(accounts.refresh("refresh-1"))
                .thenThrow(new EpicAuthException(EpicAuthException.Reason.REFRESH_REFUSED));

        assertThatThrownBy(() -> service.accessToken(bot.getId()))
                .isInstanceOfSatisfying(EpicAuthException.class,
                        e -> assertThat(e.reason()).isEqualTo(EpicAuthException.Reason.REFRESH_REFUSED));

        assertThat(bot.getAuthState()).isEqualTo(AuthState.REAUTH_REQUIRED);
        assertThat(encryption.decrypt(bot.getRefreshTokenEnc())).isEqualTo("refresh-1");

        // A bot waiting for a new login is never refreshed again
        assertThatThrownBy(() -> service.accessToken(bot.getId())).isInstanceOf(EpicAuthException.class);
        verify(accounts, times(1)).refresh(anyString());
    }

    @Test
    void transportFailure_keepsTheBotReady() {
        when(accounts.refresh("refresh-1")).thenThrow(new IllegalStateException("timeout"));

        assertThatThrownBy(() -> service.accessToken(bot.getId())).isInstanceOf(IllegalStateException.class);

        assertThat(bot.getAuthState()).isEqualTo(AuthState.READY);
        verify(repository, never()).save(any());
    }

    @Test
    void tokenToString_neverShowsTheToken() {
        assertThat(new EpicTokenService.AccessToken("secret-access", NOW).toString())
                .doesNotContain("secret-access");
        assertThat(List.of(new EpicTokenService.AccessToken("secret-access", NOW)).toString())
                .doesNotContain("secret-access");
    }

    /** A clock the test moves. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
