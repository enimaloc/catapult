package fr.enimaloc.catapult.service.enrichment.steamstats;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.PlatformStats;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SteamStatsServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String APP = "440";

    private final GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
    private final SteamStatsClient client = mock(SteamStatsClient.class);
    private final TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    private final Map<String, GameEnrichmentCacheEntry> store = new ConcurrentHashMap<>();
    private SteamStatsService service;
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
        service = new SteamStatsService(new EnrichmentCache(repository, new EnrichmentProperties(), MAPPER),
                client, encryption, new EnrichmentProperties());
        user = account("7656");
        happyUpstream();
    }

    private static UserAccount account(String steamId) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setSteamId(steamId);
        return account;
    }

    private Optional<PlatformStats> stats(boolean detail) {
        return service.forGame(user, APP, Locale.ENGLISH, FetchBudget.UNLIMITED, detail);
    }

    /** Six achievements, five unlocked at increasing dates, the rarest being the third. */
    private void happyUpstream() {
        Map<String, SteamLibrary.Entry> games = new HashMap<>();
        games.put(APP, new SteamLibrary.Entry(7320, 95, Instant.parse("2026-11-21T20:14:00Z")));
        when(client.library(anyString(), any())).thenReturn(Optional.of(new SteamLibrary(games)));
        Map<String, Instant> unlocked = new LinkedHashMap<>();
        List<SteamAchievementSchema.Definition> definitions = new java.util.ArrayList<>();
        Map<String, BigDecimal> percentages = new HashMap<>();
        for (int i = 1; i <= 7; i++) {
            definitions.add(new SteamAchievementSchema.Definition("A" + i, "Name" + i, "Desc" + i, "icon" + i,
                    "gray" + i, i == 7));
            percentages.put("A" + i, i == 3 ? new BigDecimal("0.4") : new BigDecimal("10." + i));
            if (i <= 6) {
                unlocked.put("A" + i, Instant.parse("2026-11-0" + i + "T10:00:00Z"));
            }
        }
        when(client.playerAchievements(anyString(), eq(APP), anyString(), any()))
                .thenReturn(new SteamPlayerAchievements(Status.OK, unlocked));
        when(client.schema(eq(APP), anyString())).thenReturn(Optional.of(new SteamAchievementSchema(definitions)));
        when(client.globalPercentages(APP)).thenReturn(percentages);
    }

    @Test
    void okStatsCarryPlaytimeAndTheSummary() {
        PlatformStats result = stats(false).orElseThrow();

        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.id()).isEqualTo(APP);
        assertThat(result.playtimeMinutes()).isEqualTo(7320);
        assertThat(result.playtimeTwoWeeksMinutes()).isEqualTo(95);
        assertThat(result.lastPlayed()).isEqualTo(Instant.parse("2026-11-21T20:14:00Z"));
        assertThat(result.achievements().unlocked()).isEqualTo(6);
        assertThat(result.achievements().total()).isEqualTo(7);
        assertThat(result.achievements().percent()).isEqualByComparingTo("85.7");
        assertThat(result.all()).isNull();
    }

    @Test
    void recentAreTheFiveLatestAndRarestIsLowestPercent() {
        PlatformStats result = stats(false).orElseThrow();

        assertThat(result.achievements().recent()).extracting(a -> a.id())
                .containsExactly("A6", "A5", "A4", "A3", "A2");
        assertThat(result.achievements().rarest().id()).isEqualTo("A3");
        assertThat(result.achievements().rarest().rarity()).isEqualByComparingTo("0.4");
    }

    @Test
    void detailListsEverythingWithIconsAndSecrets() {
        PlatformStats result = stats(true).orElseThrow();

        assertThat(result.all()).hasSize(7);
        assertThat(result.all().get(0).iconUrl()).isEqualTo("icon1");
        PersonalStats.Achievement secret = result.all().get(6);
        assertThat(secret.unlocked()).isFalse();
        assertThat(secret.iconUrl()).isEqualTo("gray7");
        assertThat(secret.secret()).isTrue();
        assertThat(secret.name()).isNull();
        assertThat(secret.description()).isNull();
        assertThat(secret.rarity()).isEqualByComparingTo("10.7");
    }

    @Test
    void privateProfileIsPrivateNotZero() {
        when(client.playerAchievements(anyString(), eq(APP), anyString(), any()))
                .thenReturn(SteamPlayerAchievements.of(Status.PRIVATE));

        PlatformStats result = stats(false).orElseThrow();

        assertThat(result.status()).isEqualTo(Status.PRIVATE);
        assertThat(result.achievements()).isNull();
    }

    @Test
    void privateLibraryIsPrivate() {
        when(client.library(anyString(), any())).thenReturn(Optional.empty());

        PlatformStats result = stats(false).orElseThrow();

        assertThat(result.status()).isEqualTo(Status.PRIVATE);
        assertThat(result.playtimeMinutes()).isNull();
        verify(client, never()).playerAchievements(anyString(), anyString(), anyString(), any());
    }

    @Test
    void gameNotInLibraryIsNotOwned() {
        when(client.library(anyString(), any())).thenReturn(Optional.of(new SteamLibrary(Map.of())));

        PlatformStats result = stats(false).orElseThrow();

        assertThat(result.status()).isEqualTo(Status.NOT_OWNED);
        assertThat(result.playtimeMinutes()).isNull();
    }

    @Test
    void gameWithoutAchievementsKeepsThePlaytime() {
        when(client.playerAchievements(anyString(), eq(APP), anyString(), any()))
                .thenReturn(SteamPlayerAchievements.of(Status.NO_ACHIEVEMENTS));

        PlatformStats result = stats(false).orElseThrow();

        assertThat(result.status()).isEqualTo(Status.NO_ACHIEVEMENTS);
        assertThat(result.playtimeMinutes()).isEqualTo(7320);
        assertThat(result.achievements()).isNull();
    }

    @Test
    void schemaAndRarityAreSharedAcrossMembers() {
        UserAccount other = account("7657");

        stats(false);
        service.forGame(other, APP, Locale.ENGLISH, FetchBudget.UNLIMITED, false);

        verify(client, times(1)).schema(eq(APP), anyString());
        verify(client, times(1)).globalPercentages(APP);
        verify(client, times(2)).library(anyString(), any());
    }

    @Test
    void unavailableIsNotCached() {
        when(client.library(anyString(), any())).thenThrow(new SteamStatsUnavailableException("down"))
                .thenReturn(Optional.of(new SteamLibrary(Map.of(APP, new SteamLibrary.Entry(1, 0, null)))));

        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.OK);

        verify(client, times(2)).library(anyString(), any());
    }

    @Test
    void achievementsOutageIsUnavailableAndAskedAgain() {
        when(client.playerAchievements(anyString(), eq(APP), anyString(), any()))
                .thenThrow(new SteamStatsUnavailableException("down"));

        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);

        verify(client, times(2)).playerAchievements(anyString(), eq(APP), anyString(), any());
    }

    @Test
    void rarityOutageOnlyLeavesTheRarityOut() {
        when(client.globalPercentages(APP)).thenThrow(new SteamStatsUnavailableException("down"));

        PlatformStats result = stats(false).orElseThrow();

        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.achievements().rarest()).isNull();
    }

    @Test
    void anyFailureOfTheFetchIsUnavailableNotPrivate() {
        when(client.library(anyString(), any())).thenThrow(new IllegalStateException("boom"));

        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void disabledAchievementsSourceIsUnavailableButKeepsThePlaytime() {
        EnrichmentProperties properties = new EnrichmentProperties();
        properties.getSteamSchema().setEnabled(false);
        service = new SteamStatsService(new EnrichmentCache(repository, properties, MAPPER), client, encryption,
                properties);

        PlatformStats result = stats(false).orElseThrow();

        assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.playtimeMinutes()).isEqualTo(7320);
        assertThat(result.achievements()).isNull();
    }

    @Test
    void noSteamIdMeansNothing() {
        user.setSteamId(null);

        assertThat(stats(false)).isEmpty();
        verify(client, never()).library(anyString(), any());
    }

    @Test
    void personalKeyIsDecryptedAndUsed() {
        user.setSteamPersonalToken("enc");
        when(encryption.decrypt("enc")).thenReturn("mykey");

        stats(false);

        verify(client).library("7656", "mykey");
        verify(client).playerAchievements("7656", APP, "english", "mykey");
    }

    @Test
    void languageComesFromTheLocale() {
        service.forGame(user, APP, Locale.FRENCH, FetchBudget.UNLIMITED, false);

        verify(client).schema(APP, "french");
    }

    @Test
    void refusedBudgetPropagates() {
        FetchBudget none = new FetchBudget() {
            @Override
            public boolean tryAcquire() {
                return false;
            }

            @Override
            public Duration retryAfter() {
                return Duration.ofSeconds(7);
            }
        };

        assertThatThrownBy(() -> service.forGame(user, APP, Locale.ENGLISH, none, false))
                .isInstanceOf(UpstreamBudgetExceededException.class);
    }

    @Test
    void statusIsSerializedInLowercase() {
        assertThat(MAPPER.writeValueAsString(Status.NOT_OWNED)).isEqualTo("\"not_owned\"");
        assertThat(MAPPER.readValue("\"no_achievements\"", Status.class)).isEqualTo(Status.NO_ACHIEVEMENTS);
    }
}
