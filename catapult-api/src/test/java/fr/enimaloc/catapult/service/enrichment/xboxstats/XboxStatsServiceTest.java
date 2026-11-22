package fr.enimaloc.catapult.service.enrichment.xboxstats;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Achievement;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.PlatformStats;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import fr.enimaloc.catapult.service.xbox.XboxUserTokenService;
import fr.enimaloc.catapult.service.xbox.XboxUserTokenService.XstsSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class XboxStatsServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final XstsSession SESSION = new XstsSession("t", "uhs", "2535");

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-22T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
    private final Map<String, GameEnrichmentCacheEntry> store = new ConcurrentHashMap<>();
    private final XboxStatsClient client = mock(XboxStatsClient.class);
    private final XboxUserTokenService tokens = mock(XboxUserTokenService.class);
    private final OAuthTokenRepository oauth = mock(OAuthTokenRepository.class);
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private XboxStatsService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        when(repository.findBySourceAndLookupKey(anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(repository.save(any(GameEnrichmentCacheEntry.class))).thenAnswer(inv -> {
            GameEnrichmentCacheEntry e = inv.getArgument(0);
            store.put(e.getSource() + "|" + e.getLookupKey(), e);
            return e;
        });
        service = newService(Optional.of(tokens));
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(oauth.findByUserAndProvider(user, OAuthToken.Provider.XBOX)).thenReturn(Optional.of(new OAuthToken()));
        when(tokens.getToken(user)).thenReturn(Optional.of(SESSION));
        when(client.titleHistory(any(), any(), anyInt(), any())).thenReturn(history());
        when(client.achievements(any(), any(), anyString(), any())).thenReturn(achievements());
        when(client.minutesPlayed(any(), any(), anyString())).thenReturn(Optional.of(610L));
    }

    private XboxStatsService newService(Optional<XboxUserTokenService> token) {
        return new XboxStatsService(token, oauth, client,
                new EnrichmentCache(repository, properties, MAPPER), properties, clock);
    }

    private static XboxTitle title(String id, String name, List<String> products, Integer current, Integer total) {
        return new XboxTitle(id, name, products, "Game", current, total, 100, 1000,
                Instant.parse("2026-11-20T18:30:00Z"));
    }

    private static List<XboxTitle> history() {
        return List.of(title("1791712750", "Forza Horizon 5", List.of("9MVXMVT8ZKWC"), 12, 20),
                title("42", "Halo", List.of("AAA"), 1, 10));
    }

    private static Achievement ach(String id, boolean unlocked, String at, String rarity, boolean secret) {
        return new Achievement(id, secret && !unlocked ? null : "n" + id, secret && !unlocked ? null : "d",
                "icon", unlocked, at == null ? null : Instant.parse(at),
                rarity == null ? null : new BigDecimal(rarity), 10, secret);
    }

    private static List<Achievement> achievements() {
        return List.of(ach("1", true, "2026-11-01T00:00:00Z", "50.0", false),
                ach("2", true, "2026-11-05T00:00:00Z", null, false), ach("3", false, null, null, true));
    }

    private Optional<PlatformStats> forTitle(boolean detail) {
        return service.forTitle(user, "1791712750", Locale.FRANCE, FetchBudget.UNLIMITED, detail);
    }

    @Test
    void theCallersLocaleReachesTheClient() {
        forTitle(true);

        verify(client).titleHistory(any(), any(), anyInt(), eq(Locale.FRANCE));
        verify(client).achievements(any(), any(), anyString(), eq(Locale.FRANCE));
    }

    private Optional<String> resolve(DetectedGame detected, Optional<String> igdb, String name) {
        return service.resolveTitle(user, detected, igdb, name, Locale.FRANCE, FetchBudget.UNLIMITED);
    }

    @Test
    void numericDetectedIdIsTheTitleId() {
        Optional<String> id = resolve(new DetectedGame("1791712750", GameBinding.SourceType.XBOX, "Forza"),
                Optional.empty(), "Forza");

        assertThat(id).contains("1791712750");
        verify(client, never()).titleHistory(any(), any(), anyInt(), any());
    }

    @Test
    void productIdIsMatchedInTitleHistory() {
        assertThat(resolve(new DetectedGame("9MVXMVT8ZKWC", GameBinding.SourceType.XBOX, "x"), Optional.empty(),
                "other")).contains("1791712750");
        assertThat(resolve(new DetectedGame("g", GameBinding.SourceType.MANUAL, "x"), Optional.of("aaa"),
                "other")).contains("42");
    }

    @Test
    void failingHistoryDuringResolutionIsUnavailableNotAbsent() {
        when(client.titleHistory(any(), any(), anyInt(), any())).thenThrow(new XboxStatsUnavailableException("503"));

        assertThatThrownBy(() -> resolve(new DetectedGame("9MVXMVT8ZKWC", GameBinding.SourceType.XBOX, "x"),
                Optional.empty(), "x")).isInstanceOf(XboxStatsUnavailableException.class);

        doReturn(history()).when(client).titleHistory(any(), any(), anyInt(), any());
        assertThat(resolve(null, Optional.empty(), "Nothing like this")).isEmpty();
    }

    @Test
    void missingSessionDuringResolutionIsUnavailable() {
        when(tokens.getToken(user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolve(null, Optional.empty(), "Forza Horizon 5"))
                .isInstanceOf(XboxStatsUnavailableException.class);
    }

    @Test
    void titleNameIsMatchedWhenUnique() {
        assertThat(resolve(null, Optional.empty(), "forza  horizon 5™")).contains("1791712750");
    }

    @Test
    void ambiguousTitleNameIsAbsent() {
        when(client.titleHistory(any(), any(), anyInt(), any())).thenReturn(List.of(
                title("1", "Halo", List.of(), 1, 2), title("2", "HALO", List.of(), 1, 2)));

        assertThat(resolve(null, Optional.empty(), "Halo")).isEmpty();
    }

    @Test
    void disabledXboxIsEmpty() {
        service = newService(Optional.empty());

        assertThat(forTitle(false)).isEmpty();
        assertThat(resolve(new DetectedGame("1", GameBinding.SourceType.XBOX, "x"), Optional.empty(), "x"))
                .isEmpty();
    }

    @Test
    void noLinkIsEmpty() {
        when(oauth.findByUserAndProvider(user, OAuthToken.Provider.XBOX)).thenReturn(Optional.empty());

        assertThat(forTitle(false)).isEmpty();
    }

    @Test
    void titleMissingFromHistoryIsEmpty() {
        assertThat(service.forTitle(user, "999", Locale.FRANCE, FetchBudget.UNLIMITED, false)).isEmpty();
    }

    @Test
    void happyPathSummarizesAchievementsAndPlaytime() {
        PlatformStats stats = forTitle(true).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.id()).isEqualTo("1791712750");
        assertThat(stats.playtimeMinutes()).isEqualTo(610L);
        assertThat(stats.lastPlayed()).isEqualTo(Instant.parse("2026-11-20T18:30:00Z"));
        assertThat(stats.achievements().unlocked()).isEqualTo(2);
        assertThat(stats.achievements().total()).isEqualTo(3);
        assertThat(stats.all()).hasSize(3);
        assertThat(stats.extra()).containsEntry("gamerscore", 100).containsEntry("gamerscoreTotal", 1000);
        assertThat(forTitle(false).orElseThrow().all()).isNull();
    }

    @Test
    void forbiddenAchievementsFallBackToTitlehubSummary() {
        when(client.achievements(any(), any(), anyString(), any())).thenThrow(new XboxStatsForbiddenException("403"));

        PlatformStats stats = forTitle(false).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(stats.achievements().unlocked()).isEqualTo(12);
        assertThat(stats.achievements().total()).isEqualTo(20);
        assertThat(stats.extra()).containsEntry("gamerscore", 100);
        assertThat(stats.playtimeMinutes()).isEqualTo(610L);
    }

    @Test
    void refusalIsNotRetriedWithinAnHour() {
        when(client.achievements(any(), any(), anyString(), any())).thenThrow(new XboxStatsForbiddenException("403"));

        forTitle(false);
        now.set(now.get().plus(Duration.ofMinutes(30)));
        assertThat(forTitle(false).orElseThrow().status()).isEqualTo(Status.UNSUPPORTED);
        verify(client, times(1)).achievements(any(), any(), anyString(), any());

        now.set(now.get().plus(Duration.ofMinutes(31)));
        forTitle(false);
        verify(client, times(2)).achievements(any(), any(), anyString(), any());
    }

    @Test
    void unauthorizedAchievementsAreUnavailableAndNotMemorized() {
        when(client.achievements(any(), any(), anyString(), any()))
                .thenThrow(new XboxStatsForbiddenException("401", 401));

        assertThat(forTitle(false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(forTitle(false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
        verify(client, times(2)).achievements(any(), any(), anyString(), any());
    }

    @Test
    void forbiddenPlaytimeKeepsTheStatusAndIsNotRetried() {
        when(client.minutesPlayed(any(), any(), anyString())).thenThrow(new XboxStatsForbiddenException("403"));

        PlatformStats stats = forTitle(false).orElseThrow();
        forTitle(false);

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.playtimeMinutes()).isNull();
        verify(client, times(1)).minutesPlayed(any(), any(), anyString());
    }

    @Test
    void lockedSecretAchievementIsHidden() {
        when(client.achievements(any(), any(), anyString(), any())).thenReturn(List.of(
                new Achievement("3", "Leaked", "Leaked too", "icon", false, null, null, 10, true)));

        Achievement shown = forTitle(true).orElseThrow().all().getFirst();

        assertThat(shown.name()).isNull();
        assertThat(shown.description()).isNull();
        assertThat(shown.secret()).isTrue();
    }

    @Test
    void missingRarityIsNull() {
        PlatformStats stats = forTitle(true).orElseThrow();

        assertThat(stats.all().get(1).rarity()).isNull();
        assertThat(stats.achievements().rarest().id()).isEqualTo("1");
    }

    @Test
    void xbox360TitleKeepsTheSummaryOnly() {
        when(client.achievements(any(), any(), anyString(), any())).thenReturn(List.of());

        PlatformStats stats = forTitle(true).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.achievements().unlocked()).isEqualTo(12);
        assertThat(stats.achievements().total()).isEqualTo(20);
        assertThat(stats.all()).isEmpty();
    }

    @Test
    void titleWithoutAchievementsSaysSoWithoutCallingXbox() {
        when(client.titleHistory(any(), any(), anyInt(), any())).thenReturn(List.of(
                title("1791712750", "Forza", List.of(), 0, 0)));

        PlatformStats stats = forTitle(false).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.NO_ACHIEVEMENTS);
        assertThat(stats.playtimeMinutes()).isEqualTo(610L);
        verify(client, never()).achievements(any(), any(), anyString(), any());
    }

    @Test
    void missingSessionIsUnavailable() {
        when(tokens.getToken(user)).thenReturn(Optional.empty());

        assertThat(forTitle(false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void outageOfTheHistoryOrAchievementsIsUnavailableAndNotCached() {
        when(client.titleHistory(any(), any(), anyInt(), any())).thenThrow(new XboxStatsUnavailableException("503"));
        assertThat(forTitle(false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);

        doReturn(history()).when(client).titleHistory(any(), any(), anyInt(), any());
        doThrow(new XboxStatsUnavailableException("503")).when(client).achievements(any(), any(), anyString(), any());
        assertThat(forTitle(false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);

        doReturn(achievements()).when(client).achievements(any(), any(), anyString(), any());
        assertThat(forTitle(false).orElseThrow().status()).isEqualTo(Status.OK);
    }

    @Test
    void anyFailureOfTheFetchIsAnOutageNotAnAbsence() {
        when(client.titleHistory(any(), any(), anyInt(), any())).thenThrow(new IllegalStateException("parse"));

        assertThat(forTitle(false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void disabledSourceSaysNothingRatherThanAFalseStatus() {
        properties.getXboxAchievements().setEnabled(false);

        assertThat(forTitle(false)).isEmpty();
    }

    @Test
    void secondCallIsServedFromTheCache() {
        forTitle(false);
        forTitle(false);

        verify(client, times(1)).titleHistory(any(), any(), anyInt(), any());
        verify(client, times(1)).achievements(any(), any(), anyString(), any());
        verify(client, times(1)).minutesPlayed(any(), any(), anyString());
    }
}
