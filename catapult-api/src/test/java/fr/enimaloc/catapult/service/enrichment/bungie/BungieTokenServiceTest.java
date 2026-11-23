package fr.enimaloc.catapult.service.enrichment.bungie;

import fr.enimaloc.catapult.domain.bungie.BungieAccount;
import fr.enimaloc.catapult.repository.bungie.BungieAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.site.SiteRole;
import fr.enimaloc.catapult.site.StandbyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BungieTokenServiceTest {

    private static final UUID USER = UUID.randomUUID();

    private final TokenEncryptionService encryption = new TokenEncryptionService("0123456789abcdef0123456789abcdef");
    private final BungieAccountRepository accounts = mock(BungieAccountRepository.class);
    private final BungieClient client = mock(BungieClient.class);
    private final SiteRole siteRole = mock(SiteRole.class);
    private final ApplicationEventPublisher listener = mock(ApplicationEventPublisher.class);
    private final Map<UUID, BungieAccount> rows = new HashMap<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-23T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private BungieTokenService tokens;

    @BeforeEach
    void setUp() {
        rows.put(USER, account(USER, "14982373", "refresh-1"));
        // Like JPA outside a transaction: every read is a copy, a failed save changes nothing stored
        when(accounts.findById(any())).thenAnswer(i -> Optional.ofNullable(rows.get((UUID) i.getArgument(0)))
                .map(BungieTokenServiceTest::copy));
        when(accounts.save(any())).thenAnswer(i -> {
            BungieAccount saved = i.getArgument(0);
            rows.put(saved.getUserId(), saved);
            return saved;
        });
        tokens = new BungieTokenService(accounts, client, encryption, siteRole, listener, clock);
    }

    private BungieAccount account(UUID user, String membership, String refresh) {
        BungieAccount account = new BungieAccount(user);
        account.setMembershipId(membership);
        account.setRefreshTokenEnc(encryption.encrypt(refresh));
        account.setLinkedAt(now.get());
        account.setUpdatedAt(now.get());
        account.setVersion(1L);
        return account;
    }

    private BungieData.TokenGrant grant(String access, String refresh, String membership) {
        return new BungieData.TokenGrant(access, now.get().plusSeconds(3600), refresh,
                now.get().plus(Duration.ofDays(90)), membership);
    }

    private static BungieAccount copy(BungieAccount a) {
        BungieAccount c = new BungieAccount(a.getUserId());
        c.setMembershipId(a.getMembershipId());
        c.setDisplayName(a.getDisplayName());
        c.setDestinyMembershipId(a.getDestinyMembershipId());
        c.setMembershipType(a.getMembershipType());
        c.setRefreshTokenEnc(a.getRefreshTokenEnc());
        c.setRefreshExpiresAt(a.getRefreshExpiresAt());
        c.setInvalidSince(a.getInvalidSince());
        c.setLinkedAt(a.getLinkedAt());
        c.setUpdatedAt(a.getUpdatedAt());
        c.setVersion(a.getVersion());
        return c;
    }

    private BungieAccount row() {
        return rows.get(USER);
    }

    @Test
    void renews_keepsTheAccessTokenInMemory_andWritesTheRotatedRefreshTokenEncrypted() {
        when(client.refresh("refresh-1")).thenReturn(grant("access-1", "refresh-2", "14982373"));

        assertThat(tokens.accessToken(USER)).contains("access-1");
        assertThat(tokens.accessToken(USER)).contains("access-1");

        verify(client, times(1)).refresh(anyString());
        assertThat(row().getRefreshTokenEnc()).doesNotContain("refresh-2");
        assertThat(encryption.decrypt(row().getRefreshTokenEnc())).isEqualTo("refresh-2");
        assertThat(row().getRefreshExpiresAt()).isEqualTo(now.get().plus(Duration.ofDays(90)));
        verify(siteRole).requireActive(anyString());
    }

    @Test
    void aRefusedToken_isRenewedOnTheNextCall_butNotRightAfterARenewal() {
        when(client.refresh(anyString())).thenReturn(grant("access-1", "refresh-2", "14982373"),
                grant("access-2", "refresh-3", "14982373"));

        assertThat(tokens.accessToken(USER)).contains("access-1");
        tokens.refused(USER, "other");
        assertThat(tokens.accessToken(USER)).contains("access-1");

        tokens.refused(USER, "access-1");
        // refused just after it was renewed (an API key of another application): no new renewal for a while
        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(BungieUnavailableException.class);
        verify(client, times(1)).refresh(anyString());

        now.set(now.get().plus(BungieTokenService.REFUSED_COOLDOWN).plusSeconds(1));
        assertThat(tokens.accessToken(USER)).contains("access-2");
    }

    @Test
    void aTokenRefusedLongAfterItsRenewal_isRenewedAtOnce() {
        when(client.refresh(anyString())).thenReturn(grant("access-1", "refresh-2", "14982373"),
                grant("access-2", "refresh-3", "14982373"));
        tokens.accessToken(USER);
        now.set(now.get().plus(BungieTokenService.REFUSED_COOLDOWN).plusSeconds(1));

        tokens.refused(USER, "access-1");

        assertThat(tokens.accessToken(USER)).contains("access-2");
    }

    @Test
    void aRefusedRefresh_marksTheLinkInvalid_keepsTheEncryptedToken_andTellsTheCard() {
        String stored = row().getRefreshTokenEnc();
        when(client.refresh(anyString())).thenThrow(new BungieExceptions.RefreshRefused());

        assertThat(tokens.accessToken(USER)).isEmpty();

        assertThat(row().getRefreshTokenEnc()).isEqualTo(stored);
        assertThat(row().getInvalidSince()).isEqualTo(now.get());
        assertThat(row().usable()).isFalse();
        verify(listener).publishEvent(new BungieTokenService.LinkInvalidated(USER));
        assertThat(tokens.accessToken(USER)).isEmpty();
        verify(client, times(1)).refresh(anyString());
    }

    @Test
    void refusalsForSeveralMembers_areAnOutage_nobodysLinkIsSwept() {
        when(client.refresh(anyString())).thenThrow(new BungieExceptions.RefreshRefused());
        for (int i = 0; i < BungieTokenService.SWEEP_THRESHOLD; i++) {
            UUID other = UUID.randomUUID();
            rows.put(other, account(other, "m" + i, "r" + i));
            assertThat(tokens.accessToken(other)).isEmpty();
            assertThat(rows.get(other).getInvalidSince()).isNotNull();
        }

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(BungieUnavailableException.class);

        assertThat(row().usable()).isTrue();
        verify(listener, never()).publishEvent(new BungieTokenService.LinkInvalidated(USER));
    }

    @Test
    void aRefreshForAnotherBungieAccount_isNeverUsed() {
        when(client.refresh(anyString())).thenReturn(grant("access-x", "refresh-x", "999"));

        assertThat(tokens.accessToken(USER)).isEmpty();

        assertThat(row().getInvalidSince()).isNotNull();
        assertThat(encryption.decrypt(row().getRefreshTokenEnc())).isEqualTo("refresh-1");
        verify(listener).publishEvent(new BungieTokenService.LinkInvalidated(USER));
    }

    @Test
    void aRelinkDuringTheRefresh_isNeverOverwrittenWithTheFormerLinksTokens() {
        BungieAccount relinked = account(USER, "777", "refresh-B");
        relinked.setVersion(2L);
        when(client.refresh("refresh-1")).thenAnswer(i -> {
            rows.put(USER, relinked);
            return grant("access-A", "refresh-A2", "14982373");
        });

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(BungieUnavailableException.class);

        assertThat(row().getMembershipId()).isEqualTo("777");
        assertThat(encryption.decrypt(row().getRefreshTokenEnc())).isEqualTo("refresh-B");
        assertThat(row().getInvalidSince()).isNull();
        verify(accounts, never()).save(any());
        // the next call renews from the new link, never with A's access token
        when(client.refresh("refresh-B")).thenReturn(grant("access-B", "refresh-B2", "777"));
        assertThat(tokens.accessToken(USER)).contains("access-B");
    }

    @Test
    void aRefusedFormerToken_neverInvalidatesAFreshLink() {
        BungieAccount relinked = account(USER, "777", "refresh-B");
        relinked.setVersion(2L);
        when(client.refresh("refresh-1")).thenAnswer(i -> {
            rows.put(USER, relinked);
            throw new BungieExceptions.RefreshRefused();
        });

        assertThat(tokens.accessToken(USER)).isEmpty();

        assertThat(row().getInvalidSince()).isNull();
        assertThat(row().usable()).isTrue();
        verify(listener, never()).publishEvent(any(Object.class));
    }

    @Test
    void aRelinkTakesTheSameLock_noRenewalCrossesIt() throws Exception {
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(client.refresh(anyString())).thenReturn(grant("access-1", "refresh-2", "14982373"));
        Thread relink = Thread.ofVirtual().start(() -> tokens.locked(USER, () -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(inside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        Thread reader = Thread.ofVirtual().start(() -> tokens.accessToken(USER));
        Thread.sleep(100);
        verify(client, never()).refresh(anyString());

        release.countDown();
        relink.join(5000);
        reader.join(5000);
        verify(client).refresh(anyString());
    }

    @Test
    void aSeededLoginToken_servesTheFirstRead_withoutARenewal() {
        tokens.seed(USER, "login-access", now.get().plusSeconds(3600));

        assertThat(tokens.accessToken(USER)).contains("login-access");
        verify(client, never()).refresh(anyString());
    }

    @Test
    void aSuspectApplication_staysSuspectPastTheWindow_noLinkIsSweptLater() {
        when(client.refresh(anyString())).thenThrow(new BungieExceptions.RefreshRefused());
        for (int i = 0; i < BungieTokenService.SWEEP_THRESHOLD; i++) {
            UUID other = UUID.randomUUID();
            rows.put(other, account(other, "m" + i, "r" + i));
            tokens.accessToken(other);
        }
        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(BungieUnavailableException.class);

        now.set(now.get().plus(BungieTokenService.SWEEP_WINDOW).plusSeconds(60));

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(BungieUnavailableException.class);
        assertThat(row().usable()).isTrue();
    }

    @Test
    void anUnwrittenRotatedToken_isDropped_whenTheRowWasLinkedAgain() {
        when(client.refresh("refresh-1")).thenReturn(grant("access-1", "refresh-2", "14982373"));
        when(client.refresh("relinked")).thenReturn(grant("access-9", "refresh-9", "14982373"));
        doThrow(new DataAccessResourceFailureException("down")).when(accounts).save(any());
        tokens.accessToken(USER);

        BungieAccount relinked = account(USER, "14982373", "relinked");
        relinked.setVersion(2L);
        rows.put(USER, relinked);
        now.set(now.get().plusSeconds(3600));

        assertThat(tokens.accessToken(USER)).contains("access-9");
        verify(client, never()).refresh("refresh-2");
    }

    @Test
    void aRefusedDatabaseWrite_keepsTheRotatedTokenInMemory_andWritesItAgain() {
        when(client.refresh("refresh-1")).thenReturn(grant("access-1", "refresh-2", "14982373"));
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("down")).doAnswer(i -> {
            BungieAccount saved = i.getArgument(0);
            rows.put(saved.getUserId(), saved);
            return saved;
        }).when(accounts).save(any());

        assertThat(tokens.accessToken(USER)).contains("access-1");
        assertThat(encryption.decrypt(row().getRefreshTokenEnc())).isEqualTo("refresh-1");

        assertThat(tokens.accessToken(USER)).contains("access-1");
        assertThat(encryption.decrypt(row().getRefreshTokenEnc())).isEqualTo("refresh-2");
        verify(client, times(1)).refresh(anyString());
    }

    @Test
    void aStillUnwrittenRotatedToken_isTheOneRenewedWith() {
        when(client.refresh("refresh-1")).thenReturn(grant("access-1", "refresh-2", "14982373"));
        when(client.refresh("refresh-2")).thenReturn(new BungieData.TokenGrant("access-2", now.get().plusSeconds(7200),
                "refresh-3", now.get().plus(Duration.ofDays(90)), "14982373"));
        doThrow(new DataAccessResourceFailureException("down")).when(accounts).save(any());

        tokens.accessToken(USER);
        now.set(now.get().plusSeconds(3600));

        assertThat(tokens.accessToken(USER)).contains("access-2");
        verify(client).refresh("refresh-2");
    }

    @Test
    void anOutage_isUnavailable_andTheLinkStays() {
        when(client.refresh(anyString())).thenThrow(new BungieUnavailableException("down"));

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(BungieUnavailableException.class);

        assertThat(row().usable()).isTrue();
        verify(listener, never()).publishEvent(any(Object.class));
    }

    @Test
    void aStandbyInstance_neverRenews() {
        doThrow(new StandbyException("bungie token refresh")).when(siteRole).requireActive(anyString());

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(BungieUnavailableException.class);

        verify(client, never()).refresh(anyString());
    }

    @Test
    void noLink_orAnInvalidOne_givesNoToken() {
        rows.remove(USER);
        assertThat(tokens.accessToken(USER)).isEmpty();

        BungieAccount invalid = account(USER, "14982373", "r");
        invalid.setInvalidSince(now.get());
        rows.put(USER, invalid);
        assertThat(tokens.accessToken(USER)).isEmpty();
        verify(client, never()).refresh(anyString());
    }

    @Test
    void unlinkedDuringTheRefresh_dropsTheNewTokens() {
        when(client.refresh(anyString())).thenAnswer(i -> {
            rows.remove(USER);
            return grant("access-1", "refresh-2", "14982373");
        });

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(BungieUnavailableException.class);
        verify(accounts, never()).save(any());
        assertThat(tokens.accessToken(USER)).isEmpty();
    }

    @Test
    void aCrossedWrite_isStale_nothingIsHeld() {
        when(client.refresh(anyString())).thenReturn(grant("access-1", "refresh-2", "14982373"));
        doThrow(new OptimisticLockingFailureException("crossed")).when(accounts).save(any());

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(BungieUnavailableException.class);
        verify(accounts, times(1)).save(any());
    }

    @Test
    void evict_forgetsTheAccessToken() {
        when(client.refresh(anyString())).thenReturn(grant("access-1", "refresh-2", "14982373"),
                grant("access-2", "refresh-3", "14982373"));
        tokens.accessToken(USER);

        tokens.evict(USER);

        assertThat(tokens.accessToken(USER)).contains("access-2");
    }

    @Test
    void anUndecryptableRefreshToken_isAnOutage() {
        row().setRefreshTokenEnc("garbage");

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(BungieUnavailableException.class)
                .hasMessageNotContaining("garbage");
    }
}
