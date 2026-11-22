package fr.enimaloc.catapult.service.enrichment.rastats;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.domain.retroachievements.RetroAchievementsAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.repository.retroachievements.RetroAchievementsAccountRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.PlatformStats;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import fr.enimaloc.catapult.service.retroachievements.RetroAchievementsAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetroAchievementsStatsServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final String ULID = "01HULIDULIDULIDULIDULIDULI";
    private static final RaCredentials PERSONAL = new RaCredentials("p".repeat(32), true);
    private static final RaCredentials POOL = new RaCredentials("q".repeat(32), false);
    private static final RaUser PROFILE = new RaUser("Runner", ULID, "https://media.retroachievements.org/u.png",
            1200, 30, 4000, "Playing Super Mario World", 228, false);

    private final GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
    private final RetroAchievementsClient client = mock(RetroAchievementsClient.class);
    private final RetroAchievementsAccountRepository accounts = mock(RetroAchievementsAccountRepository.class);
    private final RetroAchievementsAccountService accountService = mock(RetroAchievementsAccountService.class);
    private final Map<String, GameEnrichmentCacheEntry> store = new ConcurrentHashMap<>();
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private RetroAchievementsStatsService service;
    private UserAccount user;
    private RetroAchievementsAccount row;

    @BeforeEach
    void setUp() {
        when(repository.findBySourceAndLookupKey(anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(repository.save(any(GameEnrichmentCacheEntry.class))).thenAnswer(inv -> {
            GameEnrichmentCacheEntry e = inv.getArgument(0);
            store.put(e.getSource() + "|" + e.getLookupKey(), e);
            return e;
        });
        service = new RetroAchievementsStatsService(new EnrichmentCache(repository, properties, MAPPER), client,
                accounts, accountService, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        row = new RetroAchievementsAccount(user.getId());
        row.setUsername("Runner");
        row.setUserUlid(ULID);
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));
        when(accountService.credentials(eq(user), eq(Optional.of(row)))).thenReturn(Optional.of(PERSONAL));
        when(accountService.credentials(eq(user), eq(Optional.empty()))).thenReturn(Optional.of(POOL));
        when(client.profile(eq(ULID), any())).thenReturn(Optional.of(PROFILE));
    }

    private static RaRecentGame game(int id, String title, Instant lastPlayed) {
        return new RaRecentGame(id, "SNES", title, null, lastPlayed, 10, 2, 1, 20, 10, 100);
    }

    private void recentlyPlayed(RaRecentGame... games) {
        when(client.recentlyPlayed(eq(ULID), anyInt(), any())).thenReturn(List.of(games));
    }

    private Optional<Integer> resolve(GameBinding.SourceType type, String name) {
        return service.resolveGame(user, new DetectedGame("x", type, name), name, FetchBudget.UNLIMITED);
    }

    private static RaAchievement achievement(int id, int points, Integer awarded, Instant earned,
                                             Instant earnedHardcore) {
        return new RaAchievement(id, "A" + id, "D" + id, points, points * 2, "https://b/" + id + ".png", id,
                null, awarded, null, earned, earnedHardcore, null, null);
    }

    private static RaGameProgress progress(int players, List<RaAchievement> achievements) {
        return new RaGameProgress(228, "Super Mario World", "SNES", "https://i/228.png", achievements.size(),
                players, 50, 2, 1, "66.67%", "33.33%", 120, "beaten-hardcore", null, achievements);
    }

    @Test
    void uniqueNameMatchIsTaken() {
        recentlyPlayed(game(1, "Zelda", NOW.minus(Duration.ofDays(1))), game(228, "Super Mario World", null));

        assertThat(resolve(GameBinding.SourceType.STEAM, "Super Mario World™")).contains(228);
    }

    @Test
    void hackAndSubsetTitlesAreIgnored() {
        recentlyPlayed(game(5, "~Hack~ Super Mario World", NOW), game(6, "Super Mario World [Subset - Bonus]", NOW),
                game(7, "~Super Mario World", NOW));

        assertThat(resolve(GameBinding.SourceType.STEAM, "Super Mario World")).isEmpty();
    }

    @Test
    void twoNameMatchesAreAbsent() {
        recentlyPlayed(game(228, "Super Mario World", NOW), game(229, "Super Mario World", NOW));

        assertThat(resolve(GameBinding.SourceType.MANUAL, "Super Mario World")).isEmpty();
    }

    @Test
    void manualGamePlayedRecentlyIsTaken() {
        recentlyPlayed(game(228, "Super Mario World", NOW.minus(Duration.ofMinutes(5))));
        assertThat(resolve(GameBinding.SourceType.MANUAL, "SNES emulator")).contains(228);
        assertThat(resolve(GameBinding.SourceType.STEAM, "SNES emulator")).isEmpty();

        store.clear();
        recentlyPlayed(game(228, "Super Mario World", NOW.minus(Duration.ofMinutes(20))));
        assertThat(resolve(GameBinding.SourceType.MANUAL, "SNES emulator")).isEmpty();
    }

    @Test
    void noAccountOrSourceOffIsAbsent() {
        when(accounts.findById(user.getId())).thenReturn(Optional.empty());
        assertThat(resolve(GameBinding.SourceType.MANUAL, "x")).isEmpty();
        assertThat(service.forGame(user, 228, FetchBudget.UNLIMITED, false)).isEmpty();
        assertThat(service.account(user, FetchBudget.UNLIMITED)).isEmpty();

        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));
        properties.getRaProgress().setEnabled(false);
        assertThat(service.forGame(user, 228, FetchBudget.UNLIMITED, false)).isEmpty();
        verify(client, never()).progress(anyInt(), anyString(), any());
    }

    @Test
    void noKeyIsAbsentNotUnavailable() {
        when(accountService.credentials(eq(user), any())).thenReturn(Optional.empty());

        assertThat(resolve(GameBinding.SourceType.MANUAL, "x")).isEmpty();
        assertThat(service.forGame(user, 228, FetchBudget.UNLIMITED, false)).isEmpty();
        assertThat(service.account(user, FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void outageIsUnavailableAndNotCached() {
        when(client.profile(eq(ULID), any())).thenThrow(new RaUnavailableException("503"));

        assertThatThrownBy(() -> resolve(GameBinding.SourceType.MANUAL, "x"))
                .isInstanceOf(RaUnavailableException.class);
        assertThat(service.forGame(user, 228, FetchBudget.UNLIMITED, false))
                .contains(PlatformStats.of(Status.UNAVAILABLE, "228"));
        assertThat(service.account(user, FetchBudget.UNLIMITED).orElseThrow().status())
                .isEqualTo(Status.UNAVAILABLE);
        assertThat(store).isEmpty();
    }

    @Test
    void anyFetchErrorIsUnavailable() {
        recentlyPlayed();
        when(client.progress(eq(228), eq(ULID), any())).thenThrow(new IllegalStateException("unreadable"));

        assertThat(service.forGame(user, 228, FetchBudget.UNLIMITED, false))
                .contains(PlatformStats.of(Status.UNAVAILABLE, "228"));
    }

    @Test
    void unknownGameIsAbsent() {
        recentlyPlayed();
        when(client.progress(eq(228), eq(ULID), any())).thenReturn(Optional.empty());

        assertThat(service.forGame(user, 228, FetchBudget.UNLIMITED, false)).isEmpty();
    }

    @Test
    void untrackedIsPrivate() {
        when(client.profile(eq(ULID), any())).thenReturn(Optional.of(new RaUser("Runner", ULID, null, 0, 0, 0,
                null, null, true)));
        recentlyPlayed();

        assertThat(service.forGame(user, 228, FetchBudget.UNLIMITED, false))
                .contains(PlatformStats.of(Status.PRIVATE, "228"));
        RaAccountView view = service.account(user, FetchBudget.UNLIMITED).orElseThrow();
        assertThat(view.status()).isEqualTo(Status.PRIVATE);
        assertThat(view.recentlyPlayed()).isNull();
        assertThat(view.totalPoints()).isNull();
        verify(client, never()).progress(anyInt(), anyString(), any());
    }

    @Test
    void rarityIsAwardedOverPlayers() {
        assertThat(RetroAchievementsStatsService.rarity(25, 100)).isEqualByComparingTo("25.00");
        assertThat(RetroAchievementsStatsService.rarity(25, 100).scale()).isEqualTo(2);
        assertThat(RetroAchievementsStatsService.rarity(25, 0)).isNull();
        assertThat(RetroAchievementsStatsService.rarity(null, 10)).isNull();
    }

    @Test
    void progressGivesSummaryPointsHardcoreAndAward() {
        Instant earlier = NOW.minus(Duration.ofDays(2));
        recentlyPlayed(game(228, "Super Mario World", NOW.minus(Duration.ofHours(1))));
        when(client.progress(eq(228), eq(ULID), any())).thenReturn(Optional.of(progress(100, List.of(
                achievement(1, 5, 25, earlier, earlier),
                achievement(2, 10, 4, NOW.minus(Duration.ofDays(1)), null),
                achievement(3, 25, 1, null, null)))));

        PlatformStats stats = service.forGame(user, 228, FetchBudget.UNLIMITED, true).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.id()).isEqualTo("228");
        assertThat(stats.playtimeMinutes()).isNull();
        assertThat(stats.lastPlayed()).isEqualTo(NOW.minus(Duration.ofHours(1)));
        assertThat(stats.achievements().unlocked()).isEqualTo(2);
        assertThat(stats.achievements().total()).isEqualTo(3);
        assertThat(stats.achievements().rarest().id()).isEqualTo("2");
        assertThat(stats.achievements().rarest().rarity()).isEqualByComparingTo("4.00");
        assertThat(stats.all()).hasSize(3);
        assertThat(stats.extra()).containsEntry(RetroAchievementsStatsService.POINTS, 15)
                .containsEntry(RetroAchievementsStatsService.POINTS_TOTAL, 40)
                .containsEntry(RetroAchievementsStatsService.HARDCORE_UNLOCKED, 1)
                .containsEntry(RetroAchievementsStatsService.HARDCORE_POINTS, 5)
                .containsEntry(RetroAchievementsStatsService.AWARD, "beaten-hardcore")
                .containsEntry(RetroAchievementsStatsService.TITLE, "Super Mario World")
                .containsEntry(RetroAchievementsStatsService.CONSOLE, "SNES")
                .containsEntry(RetroAchievementsStatsService.PLAYTIME, 120);
        assertThat(service.forGame(user, 228, FetchBudget.UNLIMITED, false).orElseThrow().all()).isNull();
    }

    @Test
    void gameWithoutAchievementsSaysSo() {
        recentlyPlayed();
        when(client.progress(eq(228), eq(ULID), any())).thenReturn(Optional.of(progress(10, List.of())));

        PlatformStats stats = service.forGame(user, 228, FetchBudget.UNLIMITED, false).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.NO_ACHIEVEMENTS);
        assertThat(stats.achievements()).isNull();
    }

    @Test
    void rejectedPersonalKeyFallsBackToPool() {
        when(client.profile(eq(ULID), eq(PERSONAL))).thenThrow(new RaKeyRejectedException());
        when(client.recentlyPlayed(eq(ULID), anyInt(), eq(POOL))).thenReturn(List.of());
        when(client.progress(eq(228), eq(ULID), eq(POOL))).thenReturn(Optional.of(progress(10, List.of(
                achievement(1, 5, 1, NOW, null)))));

        PlatformStats stats = service.forGame(user, 228, FetchBudget.UNLIMITED, false).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.OK);
        verify(accountService).markKeyInvalid(user.getId());
        verify(client, never()).recentlyPlayed(anyString(), anyInt(), eq(PERSONAL));
        verify(client, never()).progress(anyInt(), anyString(), eq(PERSONAL));
    }

    @Test
    void rejectedPoolKeyIsAnOutage() {
        when(accountService.credentials(eq(user), any())).thenReturn(Optional.of(POOL));
        when(client.profile(eq(ULID), eq(POOL))).thenThrow(new RaKeyRejectedException());

        assertThat(service.forGame(user, 228, FetchBudget.UNLIMITED, false))
                .contains(PlatformStats.of(Status.UNAVAILABLE, "228"));
        verify(accountService, never()).markKeyInvalid(any());
    }

    @Test
    void accountCarriesProfileGamesAndRecentAchievements() {
        recentlyPlayed(game(228, "Super Mario World", NOW));
        RaAchievement recent = new RaAchievement(9, "Star", "d", 10, 12, "https://b/9.png", null, null, null, null,
                NOW, null, 228, "Super Mario World");
        when(client.recentAchievements(eq(ULID), eq(1440), any())).thenReturn(List.of(recent));

        RaAccountView view = service.account(user, FetchBudget.UNLIMITED).orElseThrow();

        assertThat(view.status()).isEqualTo(Status.OK);
        assertThat(view.user()).isEqualTo("Runner");
        assertThat(view.profileUrl()).isEqualTo("https://retroachievements.org/user/Runner");
        assertThat(view.totalPoints()).isEqualTo(1200);
        assertThat(view.recentlyPlayed()).extracting(RaRecentGame::gameId).containsExactly(228);
        assertThat(view.recentAchievements()).containsExactly(recent);
    }

    @Test
    void recentSourceOffLeavesTheListUnknown() {
        recentlyPlayed();
        properties.getRaRecent().setEnabled(false);

        RaAccountView view = service.account(user, FetchBudget.UNLIMITED).orElseThrow();

        assertThat(view.status()).isEqualTo(Status.OK);
        assertThat(view.recentAchievements()).isNull();
        verify(client, never()).recentAchievements(anyString(), anyInt(), any());
    }
}
