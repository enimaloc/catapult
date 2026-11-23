package fr.enimaloc.catapult.service.enrichment.speedrun;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.domain.speedrun.SpeedrunAccount;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.repository.speedrun.SpeedrunAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpeedrunIdentityResolverTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final SpeedrunUser T1 =
            new SpeedrunUser("t1", "Streamer", "https://www.speedrun.com/users/Streamer", "https://www.twitch.tv/streamer");
    private static final SpeedrunUser N1 = new SpeedrunUser("n1", "Declared", "https://www.speedrun.com/users/Declared", null);

    private final GameEnrichmentCacheRepository cacheRepository = mock(GameEnrichmentCacheRepository.class);
    private final SpeedrunAccountRepository accounts = mock(SpeedrunAccountRepository.class);
    private final SpeedrunClient client = mock(SpeedrunClient.class);
    private final SpeedrunRateLimiter limiter = mock(SpeedrunRateLimiter.class);
    private final TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    private final Map<String, GameEnrichmentCacheEntry> store = new HashMap<>();
    private final UserAccount user = new UserAccount();
    private SpeedrunIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        when(client.charging(any())).thenReturn(client);
        when(cacheRepository.findBySourceAndLookupKey(anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(cacheRepository.save(any(GameEnrichmentCacheEntry.class))).thenAnswer(inv -> {
            GameEnrichmentCacheEntry e = inv.getArgument(0);
            store.put(e.getSource() + "|" + e.getLookupKey(), e);
            return e;
        });
        when(limiter.member()).thenReturn(FetchBudget.UNLIMITED);
        when(encryption.decrypt("enc")).thenReturn("secretkey");
        user.setId(UUID.randomUUID());
        user.setTwitchUsername("Streamer");
        when(accounts.findById(user.getId())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        resolver = new SpeedrunIdentityResolver(accounts,
                new EnrichmentCache(cacheRepository, new EnrichmentProperties(), JsonMapper.builder().build()),
                client, limiter, encryption, clock);
    }

    private SpeedrunAccount row(String declared, Instant keyCheckedAt) {
        SpeedrunAccount row = new SpeedrunAccount(user.getId());
        row.setDeclaredUsername(declared);
        if (keyCheckedAt != null) {
            row.setApiKeyEnc("enc");
            row.setKeyUserId("k1");
            row.setKeyUserName("KeyOwner");
            row.setKeyCheckedAt(keyCheckedAt);
        }
        row.setUpdatedAt(NOW);
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    void keyWinsOverTwitchAndName() {
        row("n1", NOW.minus(Duration.ofDays(1)));
        when(client.usersByTwitch("Streamer")).thenReturn(List.of(T1));
        when(client.user("n1")).thenReturn(Optional.of(N1));

        Optional<SpeedrunIdentity> identity = resolver.resolve(user, FetchBudget.UNLIMITED);

        assertThat(identity).contains(new SpeedrunIdentity("k1", "KeyOwner", "https://www.speedrun.com/users/KeyOwner",
                SpeedrunIdentity.Source.API_KEY));
        verify(client, never()).usersByTwitch(anyString());
        verify(client, never()).profile(anyString());
    }

    @Test
    void staleKeyIsRecheckedAndItsAccountRefreshed() {
        row(null, NOW.minus(Duration.ofDays(8)));
        when(client.profile("secretkey")).thenReturn(Optional.of(
                new SpeedrunUser("k1", "Renamed", "https://www.speedrun.com/users/Renamed", null)));
        when(accounts.keyRechecked(user.getId(), "enc", "k1", "Renamed", NOW)).thenReturn(1);

        Optional<SpeedrunIdentity> identity = resolver.resolve(user, FetchBudget.UNLIMITED);

        assertThat(identity).contains(new SpeedrunIdentity("k1", "Renamed", "https://www.speedrun.com/users/Renamed",
                SpeedrunIdentity.Source.API_KEY));
        verify(accounts).keyRechecked(user.getId(), "enc", "k1", "Renamed", NOW);
        verify(accounts, never()).save(any());
    }

    @Test
    void recheckOfAKeyRemovedMeanwhileDoesNotVouchForIt() {
        SpeedrunAccount row = row(null, NOW.minus(Duration.ofDays(8)));
        when(client.profile("secretkey")).thenReturn(Optional.of(
                new SpeedrunUser("k1", "Renamed", "https://www.speedrun.com/users/Renamed", null)));
        when(accounts.keyRechecked(any(), any(), any(), any(), any())).thenReturn(0);
        when(client.usersByTwitch("Streamer")).thenReturn(List.of(T1));

        assertThat(resolver.resolve(user, FetchBudget.UNLIMITED)).map(SpeedrunIdentity::source)
                .contains(SpeedrunIdentity.Source.TWITCH);
        assertThat(row.getKeyUserName()).as("the row read is left as it was").isEqualTo("KeyOwner");
    }

    @Test
    void revokedKeyFallsBackToTwitch() {
        row(null, NOW.minus(Duration.ofDays(8)));
        when(client.profile("secretkey")).thenReturn(Optional.empty());
        when(client.usersByTwitch("Streamer")).thenReturn(List.of(T1));

        Optional<SpeedrunIdentity> identity = resolver.resolve(user, FetchBudget.UNLIMITED);

        assertThat(identity).contains(new SpeedrunIdentity("t1", "Streamer", "https://www.speedrun.com/users/Streamer",
                SpeedrunIdentity.Source.TWITCH));
        verify(accounts).markKeyInvalid(user.getId(), "enc", NOW);
        verify(accounts, never()).save(any());
    }

    @Test
    void aRowReadByTheCallerIsNotReadAgain() {
        SpeedrunAccount row = row(null, NOW.minus(Duration.ofDays(1)));

        assertThat(resolver.resolve(user, Optional.of(row), FetchBudget.UNLIMITED)).map(SpeedrunIdentity::userId)
                .contains("k1");
        verify(accounts, never()).findById(any());
    }

    @Test
    void failedRecheckKeepsTheKnownAccount() {
        row(null, NOW.minus(Duration.ofDays(8)));
        when(client.profile("secretkey")).thenThrow(new SpeedrunUnavailableException("down"));

        assertThat(resolver.resolve(user, FetchBudget.UNLIMITED)).map(SpeedrunIdentity::userId).contains("k1");
        verify(accounts, never()).markKeyInvalid(any(), any(), any());
        verify(accounts, never()).keyRechecked(any(), any(), any(), any(), any());
    }

    @Test
    void failedRecheckIsNotRetriedForAnHour() {
        row(null, NOW.minus(Duration.ofDays(8)));
        when(client.profile("secretkey")).thenThrow(new SpeedrunUnavailableException("down"));
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(inv -> now.get());
        SpeedrunIdentityResolver resolver = new SpeedrunIdentityResolver(accounts,
                new EnrichmentCache(cacheRepository, new EnrichmentProperties(), JsonMapper.builder().build()),
                client, limiter, encryption, clock);

        resolver.resolve(user, FetchBudget.UNLIMITED);
        now.set(NOW.plus(Duration.ofMinutes(59)));
        assertThat(resolver.resolve(user, FetchBudget.UNLIMITED)).map(SpeedrunIdentity::userId).contains("k1");
        verify(client, times(1)).profile("secretkey");

        now.set(NOW.plus(Duration.ofMinutes(60)));
        assertThat(resolver.resolve(user, FetchBudget.UNLIMITED)).map(SpeedrunIdentity::userId).contains("k1");
        verify(client, times(2)).profile("secretkey");
    }

    @Test
    void aFailedRecheckDoesNotSpendTheBudgetAgain() {
        row(null, NOW.minus(Duration.ofDays(8)));
        when(client.profile("secretkey")).thenThrow(new SpeedrunUnavailableException("down"));
        resolver.resolve(user, FetchBudget.UNLIMITED);
        FetchBudget budget = mock(FetchBudget.class);

        assertThat(resolver.resolve(user, budget)).map(SpeedrunIdentity::userId).contains("k1");
        verifyNoInteractions(budget);
    }

    @Test
    void refusedBudgetKeepsTheKnownAccountWithoutCallingUpstream() {
        row(null, NOW.minus(Duration.ofDays(8)));

        assertThat(resolver.resolve(user, () -> false)).map(SpeedrunIdentity::source)
                .contains(SpeedrunIdentity.Source.API_KEY);
        verifyNoInteractions(client);
    }

    @Test
    void invalidKeyIsSkipped() {
        SpeedrunAccount row = row(null, NOW);
        row.setKeyInvalid(true);
        when(client.usersByTwitch("Streamer")).thenReturn(List.of(T1));

        assertThat(resolver.resolve(user, FetchBudget.UNLIMITED)).map(SpeedrunIdentity::source)
                .contains(SpeedrunIdentity.Source.TWITCH);
        verify(client, never()).profile(anyString());
    }

    @Test
    void ambiguousTwitchLinkIsNoAccount() {
        SpeedrunUser other = new SpeedrunUser("t2", "Other", "u", "https://www.twitch.tv/streamer");
        when(client.usersByTwitch("Streamer")).thenReturn(List.of(T1, other));
        assertThat(resolver.resolve(user, FetchBudget.UNLIMITED)).isEmpty();

        store.clear();
        when(client.usersByTwitch("Streamer")).thenReturn(List.of(
                new SpeedrunUser("t3", "Third", "u", "https://www.twitch.tv/other")));
        row("n1", null);
        when(client.user("n1")).thenReturn(Optional.of(N1));
        assertThat(resolver.resolve(user, FetchBudget.UNLIMITED)).map(SpeedrunIdentity::source)
                .contains(SpeedrunIdentity.Source.DECLARED);
    }

    @Test
    void declaredNameIsLast() {
        row("n1", null);
        when(client.usersByTwitch("Streamer")).thenReturn(List.of());
        when(client.user("n1")).thenReturn(Optional.of(N1));

        assertThat(resolver.resolve(user, FetchBudget.UNLIMITED)).contains(new SpeedrunIdentity("n1", "Declared",
                "https://www.speedrun.com/users/Declared", SpeedrunIdentity.Source.DECLARED));
    }

    @Test
    void lookupsAreCachedByLowerCaseKeys() {
        row("N1", null);
        when(client.usersByTwitch("Streamer")).thenReturn(List.of());
        when(client.user("N1")).thenReturn(Optional.of(N1));

        resolver.resolve(user, FetchBudget.UNLIMITED);
        resolver.resolve(user, FetchBudget.UNLIMITED);

        assertThat(store).containsKeys("speedrun-user|twitch:streamer", "speedrun-user|name:n1");
        verify(client).usersByTwitch("Streamer");
        verify(client).user("N1");
    }

    @Test
    void spentBudgetUsesWhatIsCachedAndSaysSoWhenNothingIs() {
        row("n1", null);
        assertThatThrownBy(() -> resolver.resolve(user, () -> false))
                .isInstanceOf(UpstreamBudgetExceededException.class);

        store.put("speedrun-user|name:n1",
                new GameEnrichmentCacheEntry("speedrun-user", "name:n1", JsonMapper.builder().build()
                        .writeValueAsString(N1), Instant.now()));
        assertThat(resolver.resolve(user, () -> false)).map(SpeedrunIdentity::source)
                .contains(SpeedrunIdentity.Source.DECLARED);
        verifyNoInteractions(client);
    }

    @Test
    void noRowNoTwitchIsNoAccount() {
        user.setTwitchUsername(null);

        assertThat(resolver.resolve(user, FetchBudget.UNLIMITED)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void limiterRefusalStopsTheCall() {
        row("n1", null);
        when(limiter.member()).thenReturn(() -> false);

        assertThatThrownBy(() -> resolver.resolve(user, FetchBudget.UNLIMITED))
                .isInstanceOf(UpstreamBudgetExceededException.class);
        verifyNoInteractions(client);
    }

    @Test
    void sourceSerialisesInLowerSnakeCase() {
        assertThat(JsonMapper.builder().build().writeValueAsString(SpeedrunIdentity.Source.API_KEY))
                .isEqualTo("\"api_key\"");
        assertThat(SpeedrunIdentity.Source.TWITCH.key()).isEqualTo("twitch");
        assertThat(SpeedrunIdentity.Source.DECLARED.key()).isEqualTo("declared");
    }
}
