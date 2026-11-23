package fr.enimaloc.catapult.service.enrichment.osu;

import fr.enimaloc.catapult.domain.osu.OsuAccount;
import fr.enimaloc.catapult.repository.osu.OsuAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.site.SiteRole;
import fr.enimaloc.catapult.site.StandbyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

class OsuTokenServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final long OSU_ID = 7_562_902L;

    private final TokenEncryptionService encryption = new TokenEncryptionService("0123456789abcdef0123456789abcdef");
    private final OsuAccountRepository accounts = mock(OsuAccountRepository.class);
    private final OsuClient client = mock(OsuClient.class);
    private final SiteRole siteRole = mock(SiteRole.class);
    private final ApplicationEventPublisher listener = mock(ApplicationEventPublisher.class);
    private final Map<UUID, OsuAccount> rows = new HashMap<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-23T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private OsuTokenService tokens;

    @BeforeEach
    void setUp() {
        rows.put(USER, account(USER, OSU_ID, "refresh-1"));
        // Like JPA outside a transaction: every read is a copy, a save bumps the version
        when(accounts.findById(any())).thenAnswer(i -> Optional.ofNullable(rows.get((UUID) i.getArgument(0)))
                .map(OsuTokenServiceTest::copy));
        when(accounts.saveAndFlush(any())).thenAnswer(i -> {
            OsuAccount saved = copy(i.getArgument(0));
            saved.setVersion(saved.getVersion() + 1);
            rows.put(saved.getUserId(), saved);
            return copy(saved);
        });
        when(client.me(anyString())).thenAnswer(i -> me(OSU_ID));
        tokens = new OsuTokenService(accounts, client, encryption, siteRole, listener, clock);
    }

    private OsuAccount account(UUID user, long osuId, String refresh) {
        OsuAccount account = new OsuAccount(user);
        account.setOsuUserId(osuId);
        account.setRefreshTokenEnc(encryption.encrypt(refresh));
        account.setLinkedAt(now.get());
        account.setUpdatedAt(now.get());
        account.setVersion(1L);
        return account;
    }

    private static OsuData.Me me(long id) {
        return new OsuData.Me(id, "Inspector", "osu", "FR", new OsuData.Statistics(1.0, 1, 1, 99.0, 1L, 1L, 1, 0.0,
                1, 1L, true), Map.of());
    }

    private OsuData.TokenGrant grant(String access, String refresh) {
        return new OsuData.TokenGrant(access, now.get().plusSeconds(86_400), refresh);
    }

    private static OsuAccount copy(OsuAccount a) {
        OsuAccount c = new OsuAccount(a.getUserId());
        c.setOsuUserId(a.getOsuUserId());
        c.setUsername(a.getUsername());
        c.setPlaymode(a.getPlaymode());
        c.setRefreshTokenEnc(a.getRefreshTokenEnc());
        c.setInvalidSince(a.getInvalidSince());
        c.setLinkedAt(a.getLinkedAt());
        c.setUpdatedAt(a.getUpdatedAt());
        c.setVersion(a.getVersion());
        return c;
    }

    private OsuAccount row() {
        return rows.get(USER);
    }

    @Test
    void renews_checksTheAccount_keepsTheAccessTokenInMemory_andWritesTheRotatedRefreshTokenEncrypted() {
        when(client.refresh("refresh-1")).thenReturn(grant("access-1", "refresh-2"));

        assertThat(tokens.accessToken(USER)).contains("access-1");
        assertThat(tokens.accessToken(USER)).contains("access-1");

        verify(client, times(1)).refresh(anyString());
        verify(client, times(1)).me("access-1");
        assertThat(row().getRefreshTokenEnc()).doesNotContain("refresh-2");
        assertThat(encryption.decrypt(row().getRefreshTokenEnc())).isEqualTo("refresh-2");
        verify(siteRole).requireActive(anyString());
    }

    @Test
    void aRenewalForAnotherOsuAccount_isNeverUsed_andTheLinkIsInvalid() {
        when(client.refresh(anyString())).thenReturn(grant("access-x", "refresh-x"));
        when(client.me("access-x")).thenReturn(me(999L));

        assertThat(tokens.accessToken(USER)).isEmpty();

        assertThat(row().getInvalidSince()).isNotNull();
        verify(listener).publishEvent(new OsuTokenService.LinkInvalidated(USER));
        assertThat(tokens.accessToken(USER)).isEmpty();
    }

    @Test
    void aRenewalThatCannotBeChecked_isAnOutage_butTheRotatedTokenIsKept() {
        when(client.refresh("refresh-1")).thenReturn(grant("access-1", "refresh-2"));
        when(client.me("access-1")).thenThrow(new OsuUnavailableException("down"));

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(OsuUnavailableException.class);

        assertThat(encryption.decrypt(row().getRefreshTokenEnc())).isEqualTo("refresh-2");
        assertThat(row().usable()).isTrue();
        when(client.refresh("refresh-2")).thenReturn(grant("access-2", "refresh-3"));
        when(client.me("access-2")).thenReturn(me(OSU_ID));
        assertThat(tokens.accessToken(USER)).contains("access-2");
    }

    @Test
    void aRefusedToken_isRenewedOnTheNextCall_butNotRightAfterARenewal() {
        when(client.refresh(anyString())).thenReturn(grant("access-1", "refresh-2"), grant("access-2", "refresh-3"));

        assertThat(tokens.accessToken(USER)).contains("access-1");
        tokens.refused(USER, "other");
        assertThat(tokens.accessToken(USER)).contains("access-1");

        tokens.refused(USER, "access-1");
        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(OsuUnavailableException.class);
        verify(client, times(1)).refresh(anyString());

        now.set(now.get().plus(OsuTokenService.REFUSED_COOLDOWN).plusSeconds(1));
        assertThat(tokens.accessToken(USER)).contains("access-2");
    }

    @Test
    void aRefusedRefresh_marksTheLinkInvalid_keepsTheEncryptedToken_andTellsTheCard() {
        String stored = row().getRefreshTokenEnc();
        when(client.refresh(anyString())).thenThrow(new OsuExceptions.RefreshRefused());

        assertThat(tokens.accessToken(USER)).isEmpty();

        assertThat(row().getRefreshTokenEnc()).isEqualTo(stored);
        assertThat(row().getInvalidSince()).isEqualTo(now.get());
        verify(listener).publishEvent(new OsuTokenService.LinkInvalidated(USER));
        assertThat(tokens.accessToken(USER)).isEmpty();
        verify(client, times(1)).refresh(anyString());
    }

    @Test
    void refusalsForSeveralMembers_areAnOutage_nobodysLinkIsSwept() {
        when(client.refresh(anyString())).thenThrow(new OsuExceptions.RefreshRefused());
        for (int i = 0; i < OsuTokenService.SWEEP_THRESHOLD; i++) {
            UUID other = UUID.randomUUID();
            rows.put(other, account(other, i + 1, "r" + i));
            assertThat(tokens.accessToken(other)).isEmpty();
        }

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(OsuUnavailableException.class);
        assertThat(row().usable()).isTrue();

        now.set(now.get().plus(OsuTokenService.SWEEP_WINDOW).plusSeconds(60));
        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(OsuUnavailableException.class);
        assertThat(row().usable()).isTrue();
        verify(listener, never()).publishEvent(new OsuTokenService.LinkInvalidated(USER));
    }

    @Test
    void aRelinkDuringTheRefresh_isNeverOverwrittenWithTheFormerLinksTokens() {
        OsuAccount relinked = account(USER, 777L, "refresh-B");
        relinked.setVersion(5L);
        when(client.refresh("refresh-1")).thenAnswer(i -> {
            rows.put(USER, relinked);
            return grant("access-A", "refresh-A2");
        });

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(OsuUnavailableException.class);

        assertThat(row().getOsuUserId()).isEqualTo(777L);
        assertThat(encryption.decrypt(row().getRefreshTokenEnc())).isEqualTo("refresh-B");
        assertThat(row().getInvalidSince()).isNull();
        verify(accounts, never()).saveAndFlush(any());
        when(client.refresh("refresh-B")).thenReturn(grant("access-B", "refresh-B2"));
        when(client.me("access-B")).thenReturn(me(777L));
        assertThat(tokens.accessToken(USER)).contains("access-B");
    }

    @Test
    void aRefusedFormerToken_neverInvalidatesAFreshLink() {
        OsuAccount relinked = account(USER, 777L, "refresh-B");
        relinked.setVersion(5L);
        when(client.refresh("refresh-1")).thenAnswer(i -> {
            rows.put(USER, relinked);
            throw new OsuExceptions.RefreshRefused();
        });

        assertThat(tokens.accessToken(USER)).isEmpty();

        assertThat(row().getInvalidSince()).isNull();
        verify(listener, never()).publishEvent(any(Object.class));
    }

    @Test
    void aRelinkTakesTheSameLock_noRenewalCrossesIt() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.refresh(anyString())).thenReturn(grant("access-1", "refresh-2"));
        Thread relink = Thread.ofVirtual().start(() -> tokens.locked(USER, () -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
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
        tokens.seed(USER, OSU_ID, row().getLinkedAt(), "login-access", now.get().plusSeconds(86_400));

        assertThat(tokens.accessToken(USER)).contains("login-access");
        verify(client, never()).refresh(anyString());
    }

    @Test
    void aHeldToken_neverServesALinkMadeAgainElsewhere() {
        tokens.seed(USER, OSU_ID, row().getLinkedAt(), "access-X", now.get().plusSeconds(86_400));
        // linked again to another osu! account on another instance: this one still holds X's token
        OsuAccount relinked = account(USER, 777L, "refresh-Y");
        relinked.setLinkedAt(now.get().plusSeconds(5));
        relinked.setVersion(9L);
        rows.put(USER, relinked);
        when(client.refresh("refresh-Y")).thenReturn(grant("access-Y", "refresh-Y2"));
        when(client.me("access-Y")).thenReturn(me(777L));

        assertThat(tokens.accessToken(USER)).contains("access-Y");
    }

    @Test
    void evict_alsoForgetsTheMembersReadPacing() {
        OsuPlayerPacer pacer = mock(OsuPlayerPacer.class);
        new OsuTokenService(accounts, client, encryption, siteRole, listener, clock, pacer).evict(USER);

        verify(pacer).forget(USER);
    }

    @Test
    void aHeldToken_isDropped_onceTheLinkIsRefusedOrGone() {
        tokens.seed(USER, OSU_ID, row().getLinkedAt(), "access-X", now.get().plusSeconds(86_400));
        rows.get(USER).setInvalidSince(now.get());

        assertThat(tokens.accessToken(USER)).isEmpty();
        verify(client, never()).refresh(anyString());
    }

    @Test
    void aRefusedDatabaseWrite_keepsTheRotatedTokenInMemory_andWritesItAgain() {
        when(client.refresh("refresh-1")).thenReturn(grant("access-1", "refresh-2"));
        doThrow(new DataAccessResourceFailureException("down")).doAnswer(i -> {
            OsuAccount saved = copy(i.getArgument(0));
            saved.setVersion(saved.getVersion() + 1);
            rows.put(saved.getUserId(), saved);
            return copy(saved);
        }).when(accounts).saveAndFlush(any());

        assertThat(tokens.accessToken(USER)).contains("access-1");
        assertThat(encryption.decrypt(row().getRefreshTokenEnc())).isEqualTo("refresh-1");

        assertThat(tokens.accessToken(USER)).contains("access-1");
        assertThat(encryption.decrypt(row().getRefreshTokenEnc())).isEqualTo("refresh-2");
        verify(client, times(1)).refresh(anyString());
    }

    @Test
    void anOutage_isUnavailable_andTheLinkStays() {
        when(client.refresh(anyString())).thenThrow(new OsuUnavailableException("down"));

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(OsuUnavailableException.class);

        assertThat(row().usable()).isTrue();
        verify(listener, never()).publishEvent(any(Object.class));
    }

    @Test
    void aStandbyInstance_neverRenews() {
        doThrow(new StandbyException("osu token refresh")).when(siteRole).requireActive(anyString());

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(OsuUnavailableException.class);

        verify(client, never()).refresh(anyString());
    }

    @Test
    void noLink_orAnInvalidOne_givesNoToken() {
        rows.remove(USER);
        assertThat(tokens.accessToken(USER)).isEmpty();

        OsuAccount invalid = account(USER, OSU_ID, "r");
        invalid.setInvalidSince(now.get());
        rows.put(USER, invalid);
        assertThat(tokens.accessToken(USER)).isEmpty();
        verify(client, never()).refresh(anyString());
    }

    @Test
    void unlinkedDuringTheRefresh_dropsTheNewTokens() {
        when(client.refresh(anyString())).thenAnswer(i -> {
            rows.remove(USER);
            return grant("access-1", "refresh-2");
        });

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(OsuUnavailableException.class);
        verify(accounts, never()).saveAndFlush(any());
        assertThat(tokens.accessToken(USER)).isEmpty();
    }

    @Test
    void aCrossedWrite_isStale_nothingIsHeld() {
        when(client.refresh(anyString())).thenReturn(grant("access-1", "refresh-2"));
        doThrow(new OptimisticLockingFailureException("crossed")).when(accounts).saveAndFlush(any());

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(OsuUnavailableException.class);
        verify(client, never()).me(anyString());
    }

    @Test
    void evict_forgetsTheAccessToken() {
        when(client.refresh(anyString())).thenReturn(grant("access-1", "refresh-2"), grant("access-2", "refresh-3"));
        tokens.accessToken(USER);

        tokens.evict(USER);

        assertThat(tokens.accessToken(USER)).contains("access-2");
    }

    @Test
    void anUndecryptableRefreshToken_isAnOutage() {
        rows.get(USER).setRefreshTokenEnc("garbage");

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(OsuUnavailableException.class)
                .hasMessageNotContaining("garbage");
    }
}
