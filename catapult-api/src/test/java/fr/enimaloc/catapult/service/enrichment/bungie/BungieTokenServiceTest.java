package fr.enimaloc.catapult.service.enrichment.bungie;

import fr.enimaloc.catapult.domain.bungie.BungieAccount;
import fr.enimaloc.catapult.repository.bungie.BungieAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.site.SiteRole;
import fr.enimaloc.catapult.site.StandbyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    private final TokenEncryptionService encryption = new TokenEncryptionService("0123456789abcdef0123456789abcdef");
    private final BungieAccountRepository accounts = mock(BungieAccountRepository.class);
    private final BungieClient client = mock(BungieClient.class);
    private final SiteRole siteRole = mock(SiteRole.class);
    private final org.springframework.context.ApplicationEventPublisher listener =
            mock(org.springframework.context.ApplicationEventPublisher.class);
    private final AtomicReference<BungieAccount> row = new AtomicReference<>();
    private BungieTokenService tokens;

    @BeforeEach
    void setUp() {
        BungieAccount account = new BungieAccount(USER);
        account.setMembershipId("14982373");
        account.setRefreshTokenEnc(encryption.encrypt("refresh-1"));
        account.setLinkedAt(NOW);
        account.setUpdatedAt(NOW);
        row.set(account);
        when(accounts.findById(USER)).thenAnswer(i -> Optional.ofNullable(row.get()));
        when(accounts.save(any())).thenAnswer(i -> i.getArgument(0));
        tokens = new BungieTokenService(accounts, client, encryption, siteRole, listener,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static BungieData.TokenGrant grant(String access, String refresh, String membership) {
        return new BungieData.TokenGrant(access, NOW.plusSeconds(3600), refresh, NOW.plus(Duration.ofDays(90)),
                membership);
    }

    @Test
    void renews_keepsTheAccessTokenInMemory_andWritesTheRotatedRefreshTokenEncrypted() {
        when(client.refresh("refresh-1")).thenReturn(grant("access-1", "refresh-2", "14982373"));

        assertThat(tokens.accessToken(USER)).contains("access-1");
        assertThat(tokens.accessToken(USER)).contains("access-1");

        verify(client, times(1)).refresh(anyString());
        assertThat(row.get().getRefreshTokenEnc()).doesNotContain("refresh-2");
        assertThat(encryption.decrypt(row.get().getRefreshTokenEnc())).isEqualTo("refresh-2");
        assertThat(row.get().getRefreshExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
        verify(siteRole).requireActive(anyString());
    }

    @Test
    void aRefusedToken_isRenewedOnTheNextCall() {
        when(client.refresh(anyString())).thenReturn(grant("access-1", "refresh-2", "14982373"),
                grant("access-2", "refresh-3", "14982373"));

        assertThat(tokens.accessToken(USER)).contains("access-1");
        tokens.refused(USER, "other");
        assertThat(tokens.accessToken(USER)).contains("access-1");
        tokens.refused(USER, "access-1");
        assertThat(tokens.accessToken(USER)).contains("access-2");
    }

    @Test
    void aRefusedRefresh_marksTheLinkInvalid_andTellsTheCard() {
        when(client.refresh(anyString())).thenThrow(new BungieExceptions.RefreshRefused());

        assertThat(tokens.accessToken(USER)).isEmpty();

        assertThat(row.get().getRefreshTokenEnc()).isNull();
        assertThat(row.get().getInvalidSince()).isEqualTo(NOW);
        assertThat(row.get().usable()).isFalse();
        verify(listener).publishEvent(new BungieTokenService.LinkInvalidated(USER));
        // no second refresh: the link is no longer usable
        assertThat(tokens.accessToken(USER)).isEmpty();
        verify(client, times(1)).refresh(anyString());
    }

    @Test
    void aRefreshForAnotherBungieAccount_isNeverUsed() {
        when(client.refresh(anyString())).thenReturn(grant("access-x", "refresh-x", "999"));

        assertThat(tokens.accessToken(USER)).isEmpty();

        assertThat(row.get().getRefreshTokenEnc()).isNull();
        assertThat(row.get().getInvalidSince()).isNotNull();
        verify(listener).publishEvent(new BungieTokenService.LinkInvalidated(USER));
    }

    @Test
    void anOutage_isUnavailable_andTheLinkStays() {
        when(client.refresh(anyString())).thenThrow(new BungieUnavailableException("down"));

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(BungieUnavailableException.class);

        assertThat(row.get().usable()).isTrue();
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
        row.set(null);
        assertThat(tokens.accessToken(USER)).isEmpty();

        BungieAccount invalid = new BungieAccount(USER);
        invalid.setMembershipId("14982373");
        invalid.setInvalidSince(NOW);
        row.set(invalid);
        assertThat(tokens.accessToken(USER)).isEmpty();
        verify(client, never()).refresh(anyString());
    }

    @Test
    void unlinkedDuringTheRefresh_dropsTheNewTokens() {
        when(client.refresh(anyString())).thenAnswer(i -> {
            row.set(null);
            return grant("access-1", "refresh-2", "14982373");
        });

        assertThat(tokens.accessToken(USER)).isEmpty();
        verify(accounts, never()).save(any());
    }

    @Test
    void aCrossedWrite_isRedoneOnce_thenIsAnOutage() {
        when(client.refresh(anyString())).thenReturn(grant("access-1", "refresh-2", "14982373"));
        when(accounts.save(any())).thenThrow(new OptimisticLockingFailureException("crossed"));

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(BungieUnavailableException.class);
        verify(accounts, times(2)).save(any());
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
        row.get().setRefreshTokenEnc("garbage");

        assertThatThrownBy(() -> tokens.accessToken(USER)).isInstanceOf(BungieUnavailableException.class)
                .hasMessageNotContaining("garbage");
    }
}
