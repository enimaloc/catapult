package fr.enimaloc.catapult.service.osu;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.OsuAccountData;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.osu.OsuAccount;
import fr.enimaloc.catapult.repository.osu.OsuAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.osu.OsuClient;
import fr.enimaloc.catapult.service.enrichment.osu.OsuData;
import fr.enimaloc.catapult.service.enrichment.osu.OsuTokenService;
import fr.enimaloc.catapult.service.enrichment.osu.OsuUnavailableException;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.OptimisticLockingFailureException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OsuLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private final TokenEncryptionService encryption = new TokenEncryptionService("0123456789abcdef0123456789abcdef");
    private final OsuAccountRepository accounts = mock(OsuAccountRepository.class);
    private final OsuClient client = mock(OsuClient.class);
    private final OsuCredentialsService credentials = mock(OsuCredentialsService.class);
    private final OsuTokenService tokens = mock(OsuTokenService.class);
    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final ChannelEventPublisher events = mock(ChannelEventPublisher.class);
    private final AtomicReference<OsuAccount> row = new AtomicReference<>();
    private OsuLinkService links;
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
        when(client.me(any())).thenReturn(me(42L));
        when(tokens.locked(any(), any())).thenAnswer(i ->
                ((java.util.function.Supplier<?>) i.getArgument(1)).get());
        links = new OsuLinkService(accounts, client, credentials, tokens, encryption, cache, events,
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OsuData.Me me(long id) {
        return new OsuData.Me(id, "Inspector", "osu", "FR", new OsuData.Statistics(1.0, 1, 1, 99.0, 1L, 1L, 1, 0.0,
                1, 1L, true), Map.of());
    }

    private static String jwt(String claims) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    @Test
    void check_readsTheAccount_andWritesNothing() {
        OsuLinkService.PendingLink pending = links.check(jwt("{\"sub\":\"42\",\"scopes\":[\"identify\",\"public\"]}"));

        assertThat(pending).isEqualTo(new OsuLinkService.PendingLink(42L, "Inspector", "osu"));
        verify(accounts, never()).saveAndFlush(any());
    }

    @Test
    void check_refusesATokenNamingAnotherAccountThanTheOneItReads() {
        assertThatThrownBy(() -> links.check(jwt("{\"sub\":\"99\"}")))
                .isInstanceOfSatisfying(OsuLinkService.RefusedException.class,
                        e -> assertThat(e.reason()).isEqualTo(OsuLinkService.Refusal.MISMATCH));
    }

    @Test
    void check_aSubjectThatIsNotAUserId_fallsBackToTheAccountRead() {
        assertThat(links.check(jwt("{\"sub\":\"user-42\"}")).osuUserId()).isEqualTo(42L);
        assertThat(links.check(jwt("{\"aud\":\"12345\"}")).osuUserId()).isEqualTo(42L);
    }

    @Test
    void check_anOpaqueToken_isTheAccountItReads() {
        assertThat(links.check("opaque-token").osuUserId()).isEqualTo(42L);
        assertThat(OsuLinkService.tokenSubject("a.%%%.c")).isEmpty();
        assertThat(OsuLinkService.tokenSubject(null)).isEmpty();
    }

    @Test
    void check_withoutAnApplication_orWhenOsuFails() {
        when(client.me(any())).thenThrow(new OsuUnavailableException("down"));
        assertThatThrownBy(() -> links.check("token"))
                .isInstanceOfSatisfying(OsuLinkService.RefusedException.class,
                        e -> assertThat(e.reason().code()).isEqualTo("osu_failed"));

        when(credentials.available()).thenReturn(false);
        assertThatThrownBy(() -> links.check("token"))
                .isInstanceOfSatisfying(OsuLinkService.RefusedException.class,
                        e -> assertThat(e.reason().code()).isEqualTo("osu_unavailable"));
    }

    @Test
    void complete_writesTheRefreshTokenEncrypted_underTheLock_thenTellsTheCard() {
        links.complete(user, new OsuLinkService.PendingLink(42L, "Inspector", "osu"), "refresh-1", "access-1",
                NOW.plusSeconds(86_400));

        OsuAccount written = row.get();
        assertThat(written.getOsuUserId()).isEqualTo(42L);
        assertThat(written.getUsername()).isEqualTo("Inspector");
        assertThat(written.getRefreshTokenEnc()).doesNotContain("refresh-1");
        assertThat(encryption.decrypt(written.getRefreshTokenEnc())).isEqualTo("refresh-1");
        assertThat(written.getLinkedAt()).isEqualTo(NOW);
        assertThat(written.toString()).doesNotContain(written.getRefreshTokenEnc());
        InOrder order = inOrder(tokens, accounts, cache, events);
        order.verify(tokens).locked(eq(user.getId()), any());
        order.verify(accounts).saveAndFlush(any());
        order.verify(tokens).seed(user.getId(), 42L, NOW, "access-1", NOW.plusSeconds(86_400));
        order.verify(cache).evictUser(user.getId(), List.of(EnrichmentSource.OSU_PROFILE, EnrichmentSource.OSU_RECENT));
        order.verify(events).osuAccountChanged(eq(user.getId()), any());
    }

    @Test
    void linkingTheSameAccountAgain_keepsTheCache_andClearsARefusal() {
        OsuAccount existing = new OsuAccount(user.getId());
        existing.setOsuUserId(42L);
        existing.setLinkedAt(NOW.minusSeconds(3600));
        existing.setInvalidSince(NOW.minusSeconds(60));
        row.set(existing);

        links.complete(user, new OsuLinkService.PendingLink(42L, "Inspector", "osu"), "refresh-2", null, null);

        assertThat(row.get().getInvalidSince()).isNull();
        assertThat(row.get().getLinkedAt()).isEqualTo(NOW.minusSeconds(3600));
        verify(cache, never()).evictUser(any(), any());
    }

    @Test
    void complete_withoutARefreshToken_isRefused() {
        assertThatThrownBy(() -> links.complete(user, new OsuLinkService.PendingLink(42L, null, null), " ", null,
                null)).isInstanceOf(OsuLinkService.RefusedException.class);
        verify(accounts, never()).saveAndFlush(any());
    }

    @Test
    void aWriteCrossingAnotherTwice_isA409() {
        when(accounts.saveAndFlush(any())).thenThrow(new OptimisticLockingFailureException("crossed"));

        assertThatThrownBy(() -> links.complete(user, new OsuLinkService.PendingLink(42L, null, null), "r", null,
                null)).isInstanceOf(ApiProblem.class);
        verify(accounts, times(2)).saveAndFlush(any());
    }

    @Test
    void unlink_purgesTheRow_forgetsTheStatsAndTokens_thenTells() {
        links.unlink(user);

        InOrder order = inOrder(accounts, tokens, cache, events);
        order.verify(accounts).purgeByUserId(user.getId());
        order.verify(tokens).evict(user.getId());
        order.verify(cache).evictUser(user.getId(), List.of(EnrichmentSource.OSU_PROFILE, EnrichmentSource.OSU_RECENT));
        order.verify(events).osuAccountChanged(user.getId(), OsuAccountData.unlinked(true));
    }

    @Test
    void theCard_namesOnly_andNoCardWithoutAnApplicationNorALink() {
        assertThat(links.data(user)).isEqualTo(OsuAccountData.unlinked(true));

        OsuAccount account = new OsuAccount(user.getId());
        account.setOsuUserId(42L);
        account.setUsername("Inspector");
        account.setRefreshTokenEnc("enc");
        account.setLinkedAt(NOW);
        row.set(account);
        assertThat(links.data(user)).isEqualTo(new OsuAccountData(true, "Inspector", false, true, NOW));
        account.setInvalidSince(NOW);
        assertThat(links.data(user).invalid()).isTrue();

        row.set(null);
        when(credentials.available()).thenReturn(false);
        assertThat(links.data(user)).isNull();
    }

    @Test
    void aRefusedLink_tellsTheCard_andAFailingPublisherFailsNothing() {
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down")).when(events)
                .osuAccountChanged(any(), any());

        links.onLinkInvalidated(new OsuTokenService.LinkInvalidated(user.getId()));

        verify(events).osuAccountChanged(eq(user.getId()), any());
    }
}
