package fr.enimaloc.catapult.service.enrichment.osu;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.domain.osu.OsuAccount;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.repository.osu.OsuAccountRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.OsuGame;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import fr.enimaloc.catapult.service.osu.OsuCredentialsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OsuStatsServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long OSU_ID = 7_562_902L;
    private static final Instant WHEN = Instant.parse("2026-11-22T19:58:12Z");

    private final GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
    private final OsuClient client = mock(OsuClient.class);
    private final OsuTokenService tokens = mock(OsuTokenService.class);
    private final OsuCredentialsService credentials = mock(OsuCredentialsService.class);
    private final OsuAccountRepository accounts = mock(OsuAccountRepository.class);
    private final Map<String, GameEnrichmentCacheEntry> store = new ConcurrentHashMap<>();
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-23T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private OsuStatsService service;
    private UserAccount user;
    private OsuAccount account;

    @BeforeEach
    void setUp() {
        when(repository.findBySourceAndLookupKey(anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(repository.save(any(GameEnrichmentCacheEntry.class))).thenAnswer(inv -> {
            GameEnrichmentCacheEntry e = inv.getArgument(0);
            store.put(e.getSource() + "|" + e.getLookupKey(), e);
            return e;
        });
        EnrichmentCache cache = new EnrichmentCache(repository, properties, MAPPER);
        service = new OsuStatsService(cache, client, tokens, credentials, accounts, properties, clock);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(credentials.available()).thenReturn(true);
        account = new OsuAccount(user.getId());
        account.setOsuUserId(OSU_ID);
        account.setUsername("OldName");
        account.setPlaymode("osu");
        account.setRefreshTokenEnc("enc");
        when(accounts.findById(user.getId())).thenAnswer(i -> Optional.ofNullable(account));
        when(tokens.accessToken(user.getId())).thenReturn(Optional.of("token"));
        when(client.me("token")).thenReturn(me(OSU_ID));
        when(client.recentScores(OSU_ID, "taiko", OsuStatsService.RECENT, "token")).thenReturn(List.of(
                score(1, "S", 256.43, 97.512), score(2, "A", null, 93.1)));
    }

    private static OsuData.Statistics statistics(double pp, Integer rank) {
        return new OsuData.Statistics(pp, rank, rank == null ? null : 678, 98.7654, 23_456L, 1_234_567L, 101, 42.0,
                1_234, 9_876_543_210L, rank != null);
    }

    private static OsuData.Me me(long id) {
        return new OsuData.Me(id, "Inspector", "taiko", "FR", statistics(4321.5, 12_345),
                Map.of("osu", statistics(100, null), "taiko", statistics(4321.5, 12_345)));
    }

    private static OsuData.Score score(long id, String rank, Double pp, double accuracy) {
        return new OsuData.Score(id, "taiko", rank, pp, accuracy, 790, List.of("HD"), true, WHEN.minusSeconds(id),
                new OsuData.Beatmap(1000 + id, "Some Song", "Some Artist", "Insane", 5.42,
                        "https://osu.ppy.sh/b/" + (1000 + id)));
    }

    private Optional<OsuStats> stats(boolean detail) {
        return service.forGame(user, OsuGame.OSU, FetchBudget.UNLIMITED, detail);
    }

    @Test
    void readsTheProfileAndLatestScore_onTheDefaultRuleset_withTheMembersToken() {
        OsuStats stats = stats(false).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.game()).isEqualTo(OsuGame.OSU);
        assertThat(stats.account()).isEqualTo("Inspector");
        assertThat(stats.more()).isNull();
        OsuStats.Profile osu = stats.osu();
        assertThat(osu.ruleset()).isEqualTo("taiko");
        assertThat(osu.pp()).isEqualTo(4321.5);
        assertThat(osu.globalRank()).isEqualTo(12_345);
        assertThat(osu.countryRank()).isEqualTo(678);
        assertThat(osu.country()).isEqualTo("FR");
        assertThat(osu.accuracy()).isEqualTo(98.77);
        assertThat(osu.playCount()).isEqualTo(23_456L);
        assertThat(osu.playTimeSeconds()).isEqualTo(1_234_567L);
        assertThat(osu.level()).isEqualTo(101);
        assertThat(osu.lastScore().title()).isEqualTo("Some Song");
        assertThat(osu.lastScore().rank()).isEqualTo("S");
        assertThat(osu.lastScore().pp()).isEqualTo(256.43);
        assertThat(osu.lastScore().accuracy()).isEqualTo(97.51);
        assertThat(osu.lastScore().mods()).containsExactly("HD");
        // the detail parts are absent from the aggregate
        assertThat(osu.rulesets()).isNull();
        assertThat(osu.recentScores()).isNull();
    }

    @Test
    void theDetail_addsEveryRulesetAndTheLatestScores() {
        OsuStats.Profile osu = stats(true).orElseThrow().osu();

        assertThat(osu.rulesets()).containsOnlyKeys("osu", "taiko");
        assertThat(osu.rulesets().get("osu").globalRank()).isNull();
        assertThat(osu.recentScores()).hasSize(2);
        assertThat(osu.recentScores().get(1).pp()).isNull();
    }

    @Test
    void theProfileIsCached_andThePlayerIsAskedAtMostOnceAMinute() {
        stats(false);
        stats(false);

        verify(client, times(1)).me(anyString());
        verify(client, times(1)).recentScores(anyLong(), anyString(), anyInt(), anyString());
    }

    @Test
    void anOutage_isUnavailable_neverCached_andThePlayerIsNotAskedAgainWithinAMinute() {
        when(client.me("token")).thenThrow(new OsuUnavailableException("down")).thenReturn(me(OSU_ID));

        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
        verify(client, times(1)).me(anyString());

        now.set(now.get().plus(OsuStatsService.PER_PLAYER).plusSeconds(1));
        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.OK);
        verify(client, times(2)).me(anyString());
    }

    @Test
    void theScoresArePart_withoutThemTheProfileIsStillServed() {
        when(client.recentScores(anyLong(), anyString(), anyInt(), anyString()))
                .thenThrow(new OsuUnavailableException("down"));

        OsuStats stats = stats(false).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.osu().lastScore()).isNull();
    }

    @Test
    void aRefusedTokenIsRenewedOnce() {
        when(tokens.accessToken(user.getId())).thenReturn(Optional.of("token"), Optional.of("token-2"));
        when(client.me("token")).thenThrow(new OsuExceptions.AuthRefused());
        when(client.me("token-2")).thenReturn(me(OSU_ID));
        when(client.recentScores(OSU_ID, "taiko", OsuStatsService.RECENT, "token-2")).thenReturn(List.of());

        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.OK);
        verify(tokens).refused(user.getId(), "token");
    }

    @Test
    void anotherAccountsProfile_isNeverServed() {
        when(client.me("token")).thenReturn(me(999L));

        assertThat(stats(false)).isEmpty();
    }

    @Test
    void aLinkThatIsNotUsable_givesNoObject() {
        when(tokens.accessToken(user.getId())).thenReturn(Optional.empty());

        assertThat(stats(false)).isEmpty();
    }

    @Test
    void noApplication_noLink_anInvalidLink_orTheSourceOff_giveNoObject() {
        when(credentials.available()).thenReturn(false);
        assertThat(stats(false)).isEmpty();
        when(credentials.available()).thenReturn(true);

        properties.getOsuProfile().setEnabled(false);
        assertThat(stats(false)).isEmpty();
        properties.getOsuProfile().setEnabled(true);

        account.setInvalidSince(now.get());
        assertThat(stats(false)).isEmpty();

        account = null;
        assertThat(stats(false)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void theRecentSourceOff_givesNoScore_andNoCall() {
        properties.getOsuRecent().setEnabled(false);

        assertThat(stats(false).orElseThrow().osu().lastScore()).isNull();
        verify(client, never()).recentScores(anyLong(), anyString(), anyInt(), anyString());
    }

    @Test
    void forDetected_appliesToOsuAndLazer_only() {
        assertThat(service.forDetected(user, "3012", FetchBudget.UNLIMITED)).isPresent();
        assertThat(service.forDetected(user, "412517", FetchBudget.UNLIMITED)).isPresent();
        assertThat(service.forDetected(user, "1", FetchBudget.UNLIMITED)).isEmpty();
        assertThat(service.forDetected(user, null, FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void thePersonalSources_areKeyedByMember() {
        assertThat(EnrichmentSource.OSU_PROFILE.personal()).isTrue();
        assertThat(EnrichmentSource.OSU_RECENT.personal()).isTrue();
        stats(false);
        assertThat(store.keySet()).allMatch(k -> k.contains("u:" + user.getId() + ":osu:"));
    }
}
