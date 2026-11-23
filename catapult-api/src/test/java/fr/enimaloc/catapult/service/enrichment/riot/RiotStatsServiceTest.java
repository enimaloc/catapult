package fr.enimaloc.catapult.service.enrichment.riot;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.domain.riot.RiotAccount;
import fr.enimaloc.catapult.domain.riot.RiotApiKeyEntry;
import fr.enimaloc.catapult.getter.riot.RiotApiKeyRotator;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.repository.riot.RiotAccountRepository;
import fr.enimaloc.catapult.repository.riot.RiotApiKeyRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.RiotGame;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.Champion;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.Champions;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.LeagueEntry;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.LiveGame;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.LolSummoner;
import fr.enimaloc.catapult.service.enrichment.riot.RiotStats.Rank;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RiotStatsServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PUUID = "puuid-1";
    private static final String KEY_ID = fr.enimaloc.catapult.service.ApiKeyHasher.id("RGAPI-k");

    private final GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
    private final Map<String, GameEnrichmentCacheEntry> store = new ConcurrentHashMap<>();
    private final RiotClient client = mock(RiotClient.class);
    private final RiotApiKeyRepository keyRepository = mock(RiotApiKeyRepository.class);
    private final RiotAccountRepository accounts = mock(RiotAccountRepository.class);
    private final fr.enimaloc.catapult.service.riot.RiotAccountService accountService =
            mock(fr.enimaloc.catapult.service.riot.RiotAccountService.class);
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private RiotApiKeyRotator keys;
    private RiotStatsService service;
    private RiotGameDataService gameData;
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
        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of(new RiotApiKeyEntry("RGAPI-k", null)));
        keys = new RiotApiKeyRotator(keyRepository, new SimpleMeterRegistry());
        EnrichmentCache cache = new EnrichmentCache(repository, properties, MAPPER);
        gameData = new RiotGameDataService(cache, client, keys, properties);
        service = new RiotStatsService(cache, client, keys, accounts, accountService, gameData, properties);
        properties.getRiot().setTftIgdbId("1234");
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        RiotAccount row = new RiotAccount(user.getId());
        row.setGameName("Catapult");
        row.setTagLine("EUW");
        row.setPuuid(PUUID);
        row.setPuuidKeyId(KEY_ID);
        row.setPlatform("euw1");
        row.setRegion("europe");
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));
        when(client.lolSummoner(RiotPlatform.EUW1, PUUID, KEY_ID)).thenReturn(new LolSummoner(312, 6));
        when(client.lolLeague(RiotPlatform.EUW1, PUUID, KEY_ID)).thenReturn(List.of(
                new LeagueEntry("RANKED_SOLO_5x5", "GOLD", "II", 57, 61, 54),
                new LeagueEntry("RANKED_FLEX_SR", "SILVER", "I", 12, 10, 9),
                new LeagueEntry("CHERRY", "UNRANKED", null, 0, 1, 1)));
        when(client.lolActiveGame(RiotPlatform.EUW1, PUUID, KEY_ID)).thenReturn(Optional.of(new LiveGame("CLASSIC",
                "MATCHED", 420, 266, Instant.parse("2026-11-23T18:00:00Z"), 754L)));
        when(client.tftLeague(RiotPlatform.EUW1, PUUID, KEY_ID)).thenReturn(List.of(
                new LeagueEntry("RANKED_TFT", "PLATINUM", "IV", 3, 22, 71)));
        when(client.latestVersion()).thenReturn("16.23.1");
        when(client.champions("16.23.1", "fr_FR")).thenReturn(new Champions("16.23.1",
                Map.of("266", new Champion("Aatrox", "Aatrox"))));
    }

    private Optional<RiotStats> stats(RiotGame game, boolean detail) {
        return service.forGame(user, game, Locale.FRENCH, FetchBudget.UNLIMITED, detail);
    }

    private void emptyPool() {
        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of());
        keys.refreshKeys();
    }

    @Test
    void lol_givesTheLevel_theRanks_andTheGameInProgress() {
        RiotStats stats = stats(RiotGame.LOL, false).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.game()).isEqualTo(RiotGame.LOL);
        assertThat(stats.account()).isEqualTo("Catapult#EUW");
        assertThat(stats.platform()).isEqualTo("euw1");
        assertThat(stats.tft()).isNull();
        RiotStats.LolStats lol = stats.lol();
        assertThat(lol.level()).isEqualTo(312);
        assertThat(lol.profileIconUrl())
                .isEqualTo("https://ddragon.leagueoflegends.com/cdn/16.23.1/img/profileicon/6.png");
        assertThat(lol.ranked().solo()).isEqualTo(new Rank("GOLD", "II", 57, 61, 54));
        assertThat(lol.ranked().flex()).isEqualTo(new Rank("SILVER", "I", 12, 10, 9));
        assertThat(lol.inGame()).isTrue();
        assertThat(lol.currentGame().champion()).isEqualTo("Aatrox");
        assertThat(lol.currentGame().championIconUrl()).endsWith("/cdn/16.23.1/img/champion/Aatrox.png");
        assertThat(lol.currentGame().queueId()).isEqualTo(420);
        assertThat(lol.queues()).isNull();
        assertThat(stats.more()).isNull();
    }

    @Test
    void theDetail_addsEveryQueue() {
        RiotStats stats = stats(RiotGame.LOL, true).orElseThrow();

        assertThat(stats.lol().queues()).extracting(RiotStats.Queue::queueType)
                .containsExactly("RANKED_SOLO_5x5", "RANKED_FLEX_SR", "CHERRY");
    }

    @Test
    void anUnrankedSummoner_inNoGame_isOk_withEmptyRanks() {
        when(client.lolLeague(RiotPlatform.EUW1, PUUID, KEY_ID)).thenReturn(List.of());
        when(client.lolActiveGame(RiotPlatform.EUW1, PUUID, KEY_ID)).thenReturn(Optional.empty());

        RiotStats.LolStats lol = stats(RiotGame.LOL, false).orElseThrow().lol();

        assertThat(lol.ranked()).isEqualTo(new RiotStats.Ranked(null, null));
        assertThat(lol.inGame()).isFalse();
        assertThat(lol.currentGame()).isNull();
    }

    @Test
    void anUnknownSummoner_isNotFound_andCached() {
        when(client.lolSummoner(RiotPlatform.EUW1, PUUID, KEY_ID)).thenThrow(new RiotNotFoundException("no"));

        assertThat(stats(RiotGame.LOL, false).orElseThrow().status()).isEqualTo(Status.NOT_FOUND);
        assertThat(stats(RiotGame.LOL, false).orElseThrow().lol().status()).isEqualTo(Status.NOT_FOUND);

        verify(client, times(1)).lolSummoner(RiotPlatform.EUW1, PUUID, KEY_ID);
        verify(client, never()).lolLeague(any(), anyString(), any());
    }

    @Test
    void anOutage_isUnavailable_andNotCached() {
        when(client.lolSummoner(RiotPlatform.EUW1, PUUID, KEY_ID)).thenThrow(new RiotUnavailableException("down"));

        assertThat(stats(RiotGame.LOL, false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(stats(RiotGame.LOL, false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);

        verify(client, times(2)).lolSummoner(RiotPlatform.EUW1, PUUID, KEY_ID);
    }

    @Test
    void anyOtherFailure_isAnOutageToo() {
        when(client.lolSummoner(RiotPlatform.EUW1, PUUID, KEY_ID)).thenThrow(new IllegalStateException("garbled"));

        assertThat(stats(RiotGame.LOL, false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void failedParts_leaveTheSummoner_withUnknownRanksAndGame() {
        when(client.lolLeague(RiotPlatform.EUW1, PUUID, KEY_ID)).thenThrow(new RiotUnavailableException("down"));
        when(client.lolActiveGame(RiotPlatform.EUW1, PUUID, KEY_ID)).thenThrow(new RiotUnavailableException("down"));
        when(client.latestVersion()).thenThrow(new RiotUnavailableException("down"));

        RiotStats.LolStats lol = stats(RiotGame.LOL, true).orElseThrow().lol();

        assertThat(lol.status()).isEqualTo(Status.OK);
        assertThat(lol.level()).isEqualTo(312);
        assertThat(lol.ranked()).isNull();
        assertThat(lol.inGame()).isNull();
        assertThat(lol.currentGame()).isNull();
        assertThat(lol.profileIconUrl()).isNull();
        assertThat(lol.queues()).isNull();
    }

    @Test
    void anUnreadableChampionList_keepsTheGame_withoutTheChampionsName() {
        when(client.champions(anyString(), anyString())).thenThrow(new RiotUnavailableException("down"));

        RiotStats.CurrentGame game = stats(RiotGame.LOL, false).orElseThrow().lol().currentGame();

        assertThat(game.championId()).isEqualTo(266);
        assertThat(game.champion()).isNull();
        assertThat(game.championIconUrl()).isNull();
    }

    @Test
    void tft_givesTheRank() {
        RiotStats stats = stats(RiotGame.TFT, true).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.lol()).isNull();
        assertThat(stats.tft().rank()).isEqualTo(new Rank("PLATINUM", "IV", 3, 22, 71));
        assertThat(stats.tft().queues()).hasSize(1);
        assertThat(stats(RiotGame.TFT, false).orElseThrow().tft().queues()).isNull();
    }

    @Test
    void tft_outage_isUnavailable_andUnranked_isOkWithoutRank() {
        when(client.tftLeague(RiotPlatform.EUW1, PUUID, KEY_ID)).thenThrow(new RiotUnavailableException("down"));
        assertThat(stats(RiotGame.TFT, false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);

        org.mockito.Mockito.doReturn(List.of()).when(client).tftLeague(RiotPlatform.EUW1, PUUID, KEY_ID);
        RiotStats unranked = stats(RiotGame.TFT, false).orElseThrow();
        assertThat(unranked.status()).isEqualTo(Status.OK);
        assertThat(unranked.tft().rank()).isNull();
    }

    @Test
    void valorant_isUnsupported_withoutAnyCall() {
        RiotStats stats = stats(RiotGame.VALORANT, false).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(stats.account()).isEqualTo("Catapult#EUW");
        assertThat(stats.lol()).isNull();
        assertThat(stats.tft()).isNull();
        verifyNoInteractions(client);
    }

    @Test
    void anEmptyPool_turnsTheSourceOff_withoutAnyCall() {
        emptyPool();

        assertThat(stats(RiotGame.LOL, false)).isEmpty();
        assertThat(stats(RiotGame.VALORANT, false)).isEmpty();
        assertThat(gameData.available()).isFalse();
        assertThat(gameData.forGame(RiotGame.LOL, FetchBudget.UNLIMITED)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void aPoolWithoutAKeyForTheMembersCluster_isOffForThem() {
        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of(new RiotApiKeyEntry("RGAPI-k", "americas")));
        keys.refreshKeys();

        assertThat(stats(RiotGame.LOL, false)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void theSourceDisabled_orASubSourceDisabled_givesNothing() {
        properties.getRiot().setEnabled(false);
        assertThat(stats(RiotGame.LOL, false)).isEmpty();
        properties.getRiot().setEnabled(true);

        properties.setRiotLolSummoner(disabled());
        properties.setRiotTftLeague(disabled());
        assertThat(stats(RiotGame.LOL, false)).isEmpty();
        assertThat(stats(RiotGame.TFT, false)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void theLiveSourceDisabled_leavesTheGameUnknown() {
        properties.setRiotLolLive(disabled());

        RiotStats.LolStats lol = stats(RiotGame.LOL, false).orElseThrow().lol();

        assertThat(lol.inGame()).isNull();
        verify(client, never()).lolActiveGame(any(), anyString(), any());
    }

    @Test
    void noDeclaredRiotId_givesNothing() {
        when(accounts.findById(user.getId())).thenReturn(Optional.empty());

        assertThat(stats(RiotGame.LOL, false)).isEmpty();
    }

    @Test
    void aStoredPlatformThatIsNoLongerKnown_givesNothing() {
        accounts.findById(user.getId()).orElseThrow().setPlatform("mars1");

        assertThat(stats(RiotGame.LOL, false)).isEmpty();
    }

    @Test
    void forDetected_findsTheGameByItsIgdbId() {
        assertThat(service.forDetected(user, "115", Locale.FRENCH, FetchBudget.UNLIMITED))
                .hasValueSatisfying(s -> assertThat(s.game()).isEqualTo(RiotGame.LOL));
        assertThat(service.forDetected(user, "126459", Locale.FRENCH, FetchBudget.UNLIMITED))
                .hasValueSatisfying(s -> assertThat(s.status()).isEqualTo(Status.UNSUPPORTED));
        assertThat(service.forDetected(user, "123", Locale.FRENCH, FetchBudget.UNLIMITED)).isEmpty();
        assertThat(service.forDetected(user, null, Locale.FRENCH, FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void theCacheKeys_areTheMembers() {
        stats(RiotGame.LOL, false);

        assertThat(store.keySet()).anyMatch(k -> k.equals(EnrichmentSource.RIOT_LOL_SUMMONER.key() + "|u:"
                + user.getId() + ":lol:euw1"));
        assertThat(store.keySet()).noneMatch(k -> k.contains(PUUID));
        assertThat(store.keySet()).anyMatch(k -> k.equals("riot-lol-patch|lol-patch"));
        assertThat(store.keySet()).filteredOn(k -> k.startsWith("riot-lol-patch") || k.startsWith("riot-lol-champions"))
                .noneMatch(k -> k.contains(user.getId().toString()));
    }

    @Test
    void thePublicData_isThePatch_forLeagueOnly() {
        assertThat(gameData.forGame(RiotGame.LOL, FetchBudget.UNLIMITED))
                .contains(new RiotPublisher(RiotGame.LOL, new RiotPublisher.Lol("16.23.1")));
        assertThat(gameData.forGame(RiotGame.TFT, FetchBudget.UNLIMITED)).isEmpty();
        assertThat(gameData.forGame(RiotGame.VALORANT, FetchBudget.UNLIMITED)).isEmpty();
    }

    private static EnrichmentProperties.SourceSettings disabled() {
        EnrichmentProperties.SourceSettings settings = new EnrichmentProperties.SourceSettings();
        settings.setEnabled(false);
        return settings;
    }

    // ---------------------------------------------------------------- fix wave

    private RiotAccount reresolved(String puuid, String keyId) {
        RiotAccount fresh = new RiotAccount(user.getId());
        fresh.setGameName("Catapult");
        fresh.setTagLine("EUW");
        fresh.setPuuid(puuid);
        fresh.setPuuidKeyId(keyId);
        fresh.setPlatform("euw1");
        fresh.setRegion("europe");
        return fresh;
    }

    @Test
    void aStalePuuid_isResolvedAgainOnce_andTheStatsReadWithTheNewOne() {
        when(client.lolSummoner(RiotPlatform.EUW1, PUUID, KEY_ID)).thenThrow(new RiotStaleIdException("gone"));
        when(accountService.reresolve(user.getId())).thenReturn(Optional.of(reresolved("puuid-2", KEY_ID)));
        when(client.lolSummoner(RiotPlatform.EUW1, "puuid-2", KEY_ID)).thenReturn(new LolSummoner(313, 6));
        when(client.lolLeague(RiotPlatform.EUW1, "puuid-2", KEY_ID)).thenReturn(List.of());
        when(client.lolActiveGame(RiotPlatform.EUW1, "puuid-2", KEY_ID)).thenReturn(Optional.empty());

        RiotStats stats = stats(RiotGame.LOL, false).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.lol().level()).isEqualTo(313);
        verify(accountService, times(1)).reresolve(user.getId());
    }

    @Test
    void aRiotIdRiotNoLongerKnows_isNotFound_andAFailedResolveUnavailable_andAStaleOneTwiceToo() {
        when(client.lolSummoner(any(), anyString(), any())).thenThrow(new RiotStaleIdException("gone"));

        when(accountService.reresolve(user.getId())).thenReturn(Optional.empty());
        assertThat(stats(RiotGame.LOL, false).orElseThrow().status()).isEqualTo(Status.NOT_FOUND);

        when(accountService.reresolve(user.getId())).thenThrow(new RiotUnavailableException("down"));
        assertThat(stats(RiotGame.LOL, false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);

        org.mockito.Mockito.reset(accountService);
        when(accountService.reresolve(user.getId())).thenReturn(Optional.of(reresolved("puuid-2", KEY_ID)));
        assertThat(stats(RiotGame.LOL, false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void aForbiddenTft_isUnsupported_notCached_andLolIsUntouched() {
        when(client.tftLeague(RiotPlatform.EUW1, PUUID, KEY_ID)).thenThrow(new RiotForbiddenException("403"));

        assertThat(stats(RiotGame.TFT, false).orElseThrow().status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(stats(RiotGame.LOL, false).orElseThrow().status()).isEqualTo(Status.OK);
        assertThat(store.keySet()).noneMatch(k -> k.startsWith("riot-tft-league|"));
    }

    @Test
    void tft_isOptIn_offWithoutItsIgdbId() {
        properties.getRiot().setTftIgdbId("");

        assertThat(stats(RiotGame.TFT, true)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void aLeagueRiotDoesNotKnow_isUnranked_cachedUnderTheSourceTtl() {
        when(client.lolLeague(RiotPlatform.EUW1, PUUID, KEY_ID)).thenThrow(new RiotNotFoundException("no"));

        assertThat(stats(RiotGame.LOL, false).orElseThrow().lol().ranked()).isEqualTo(new RiotStats.Ranked(null, null));
        stats(RiotGame.LOL, false);

        verify(client, times(1)).lolLeague(RiotPlatform.EUW1, PUUID, KEY_ID);
        // a cell holding the absence: a value, under the source's TTL, not a known-absent row
        assertThat(store.get("riot-lol-league|u:" + user.getId() + ":lol:euw1").getPayload()).isNotNull();
    }

    @Test
    void aTftLeagueRiotDoesNotKnow_isNotFound_cachedAsAValue() {
        when(client.tftLeague(RiotPlatform.EUW1, PUUID, KEY_ID)).thenThrow(new RiotNotFoundException("no"));

        assertThat(stats(RiotGame.TFT, false).orElseThrow().status()).isEqualTo(Status.NOT_FOUND);
        assertThat(stats(RiotGame.TFT, false).orElseThrow().status()).isEqualTo(Status.NOT_FOUND);

        verify(client, times(1)).tftLeague(RiotPlatform.EUW1, PUUID, KEY_ID);
        assertThat(store.get("riot-tft-league|u:" + user.getId() + ":tft:euw1").getPayload()).isNotNull();
    }
}
