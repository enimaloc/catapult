package fr.enimaloc.catapult.service.bungie;

import fr.enimaloc.catapult.common.dto.channel.BungieAccountData;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.bungie.BungieAccount;
import fr.enimaloc.catapult.repository.bungie.BungieAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieClient;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieTokenService;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieUnavailableException;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BungieLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private final TokenEncryptionService encryption = new TokenEncryptionService("0123456789abcdef0123456789abcdef");
    private final BungieAccountRepository accounts = mock(BungieAccountRepository.class);
    private final BungieClient client = mock(BungieClient.class);
    private final BungieCredentialsService credentials = mock(BungieCredentialsService.class);
    private final BungieTokenService tokens = mock(BungieTokenService.class);
    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final ChannelEventPublisher events = mock(ChannelEventPublisher.class);
    private final AtomicReference<BungieAccount> row = new AtomicReference<>();
    private BungieLinkService links;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(accounts.findById(user.getId())).thenAnswer(i -> Optional.ofNullable(row.get()));
        when(accounts.saveAndFlush(any())).thenAnswer(i -> {
            row.set(i.getArgument(0));
            return i.getArgument(0);
        });
        when(credentials.available()).thenReturn(true);
        when(client.currentUser("token")).thenReturn(new BungieData.CurrentUser("42", "Guardian#0042",
                List.of(new BungieData.DestinyMembership(3, "461", 0)), null));
        when(tokens.locked(any(), any())).thenAnswer(i ->
                ((java.util.function.Supplier<?>) i.getArgument(1)).get());
        links = new BungieLinkService(accounts, client, credentials, tokens, encryption, cache, events,
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void check_readsTheAccount_andWritesNothing() {
        BungieLinkService.PendingLink pending = links.check("token",
                Map.of("membership_id", "42", "refresh_expires_in", 7_776_000));

        assertThat(pending).isEqualTo(new BungieLinkService.PendingLink("42", "Guardian#0042", 3, "461",
                NOW.plusSeconds(7_776_000)));
        assertThat(pending.toString()).doesNotContain("token");
        verify(accounts, never()).saveAndFlush(any());
    }

    @Test
    void check_refusesAnotherAccountThanTheTokensOne() {
        assertThatThrownBy(() -> links.check("token", Map.of("membership_id", "99")))
                .isInstanceOfSatisfying(BungieLinkService.RefusedException.class,
                        e -> assertThat(e.reason()).isEqualTo(BungieLinkService.Refusal.MISMATCH));
    }

    @Test
    void check_withoutAnApplication_orWithoutMembership_orWhenBungieFails() {
        assertThatThrownBy(() -> links.check("token", Map.of()))
                .isInstanceOfSatisfying(BungieLinkService.RefusedException.class,
                        e -> assertThat(e.reason().code()).isEqualTo("bungie_failed"));

        when(client.currentUser("token")).thenThrow(new BungieUnavailableException("down"));
        assertThatThrownBy(() -> links.check("token", Map.of("membership_id", "42")))
                .isInstanceOfSatisfying(BungieLinkService.RefusedException.class,
                        e -> assertThat(e.reason()).isEqualTo(BungieLinkService.Refusal.FAILED));

        when(credentials.available()).thenReturn(false);
        assertThatThrownBy(() -> links.check("token", Map.of("membership_id", "42")))
                .isInstanceOfSatisfying(BungieLinkService.RefusedException.class,
                        e -> assertThat(e.reason().code()).isEqualTo("bungie_unavailable"));
    }

    @Test
    void check_withoutADestinyProfile_stillLinks() {
        when(client.currentUser("token")).thenReturn(new BungieData.CurrentUser("42", null, List.of(), null));

        BungieLinkService.PendingLink pending = links.check("token", Map.of("membership_id", 42));

        assertThat(pending.destinyMembershipId()).isNull();
        assertThat(pending.refreshExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
    }

    @Test
    void complete_writesTheRefreshTokenEncrypted_andTellsTheCard() {
        links.complete(user, new BungieLinkService.PendingLink("42", "Guardian#0042", 3, "461", NOW.plusSeconds(60)),
                "refresh-token");

        BungieAccount account = row.get();
        assertThat(account.getMembershipId()).isEqualTo("42");
        assertThat(account.getRefreshTokenEnc()).doesNotContain("refresh-token");
        assertThat(encryption.decrypt(account.getRefreshTokenEnc())).isEqualTo("refresh-token");
        assertThat(account.getLinkedAt()).isEqualTo(NOW);
        assertThat(account.usable()).isTrue();
        verify(tokens).evict(user.getId());
        verify(cache).evictUser(eq(user.getId()), any());
        verify(events).bungieAccountChanged(user.getId(),
                new BungieAccountData(true, "Guardian#0042", "steam", false, true, NOW));
    }

    @Test
    void linkingTheSameAccountAgain_clearsAnInvalidLink_andKeepsTheCache() {
        BungieAccount existing = new BungieAccount(user.getId());
        existing.setMembershipId("42");
        existing.setInvalidSince(NOW.minusSeconds(60));
        existing.setLinkedAt(NOW.minusSeconds(3600));
        row.set(existing);

        links.complete(user, new BungieLinkService.PendingLink("42", "G#1", 3, "461", NOW), "refresh");

        assertThat(row.get().getInvalidSince()).isNull();
        assertThat(row.get().getLinkedAt()).isEqualTo(NOW.minusSeconds(3600));
        verify(cache, never()).evictUser(any(), any());
    }

    @Test
    void complete_withoutARefreshToken_writesNothing() {
        assertThatThrownBy(() -> links.complete(user, new BungieLinkService.PendingLink("42", null, null, null, NOW),
                " ")).isInstanceOf(BungieLinkService.RefusedException.class);
        verify(accounts, never()).saveAndFlush(any());
    }

    @Test
    void unlink_purgesTheRow_theCacheAndTheToken() {
        links.unlink(user);

        verify(accounts).purgeByUserId(user.getId());
        verify(cache).evictUser(user.getId(), List.of(EnrichmentSource.BUNGIE_PROFILE,
                EnrichmentSource.BUNGIE_TRIUMPHS));
        verify(tokens).evict(user.getId());
        verify(events).bungieAccountChanged(user.getId(), BungieAccountData.unlinked(true));
    }

    @Test
    void data_isNullWithoutBungieOrALink_andSaysAnInvalidLink() {
        assertThat(links.data(user)).isEqualTo(BungieAccountData.unlinked(true));
        when(credentials.available()).thenReturn(false);
        assertThat(links.data(user)).isNull();

        BungieAccount invalid = new BungieAccount(user.getId());
        invalid.setMembershipId("42");
        invalid.setMembershipType(1);
        invalid.setInvalidSince(NOW);
        invalid.setLinkedAt(NOW);
        row.set(invalid);
        assertThat(links.data(user)).isEqualTo(new BungieAccountData(true, null, "xbox", true, false, NOW));
    }

    @Test
    void aRefusedRefresh_tellsTheCard_andAFailingPublisherNeverFailsTheWrite() {
        doThrow(new IllegalStateException("redis down")).when(events).bungieAccountChanged(any(), any());

        links.onLinkInvalidated(new BungieTokenService.LinkInvalidated(user.getId()));
        links.complete(user, new BungieLinkService.PendingLink("42", null, 3, "461", NOW), "refresh");

        assertThat(row.get()).isNotNull();
        verifyNoInteractions(client);
    }

    @Test
    void complete_runsUnderTheMembersTokenLock_andHoldsTheLoginsAccessToken() {
        links.complete(user, new BungieLinkService.PendingLink("42", "G#1", 3, "461", NOW), "refresh", "access",
                NOW.plusSeconds(3600));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(tokens, accounts, events);
        order.verify(tokens).locked(eq(user.getId()), any());
        order.verify(accounts).saveAndFlush(any());
        order.verify(tokens).evict(user.getId());
        order.verify(tokens).seed(user.getId(), "access", NOW.plusSeconds(3600));
        order.verify(events).bungieAccountChanged(eq(user.getId()), any());
    }

    @Test
    void aFailedWrite_tellsNothing() {
        when(accounts.saveAndFlush(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> links.complete(user, new BungieLinkService.PendingLink("42", null, 3, "461", NOW),
                "refresh")).isInstanceOf(IllegalStateException.class);

        verify(events, never()).bungieAccountChanged(any(), any());
        verify(cache, never()).evictUser(any(), any());
    }

    @Test
    void unlink_tellsThePagesOnlyOnceDeleted() {
        doThrow(new IllegalStateException("db down")).when(accounts).purgeByUserId(user.getId());

        assertThatThrownBy(() -> links.unlink(user)).isInstanceOf(IllegalStateException.class);

        verify(events, never()).bungieAccountChanged(any(), any());
    }
}
