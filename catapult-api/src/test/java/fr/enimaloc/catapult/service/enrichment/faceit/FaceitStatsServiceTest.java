package fr.enimaloc.catapult.service.enrichment.faceit;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.domain.faceit.FaceitAccount;
import fr.enimaloc.catapult.domain.faceit.FaceitApiKeyEntry;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.faceit.FaceitApiKeyRotator;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.repository.faceit.FaceitAccountRepository;
import fr.enimaloc.catapult.repository.faceit.FaceitApiKeyRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.GameProfile;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.LastMatch;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.Lifetime;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.Player;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.FaceitGame;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

class FaceitStatsServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String ID = "player-1";
    private static final Player PLAYER = new Player(ID, "CataPult", null, "fr", null, new GameProfile(8, 1850, "EU"));
    private static final Lifetime LIFETIME = new Lifetime(420, 231, 55, 1.12, 48, 2, 9, List.of(true, false));
    private static final LastMatch LAST = new LastMatch("m1", "de_mirage", "13 / 9", true, 21, 15, 1.4,
            Instant.parse("2026-11-22T20:48:40Z"));

    private final GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
    private final Map<String, GameEnrichmentCacheEntry> store = new ConcurrentHashMap<>();
    private final FaceitClient client = mock(FaceitClient.class);
    private final FaceitApiKeyRepository keyRepository = mock(FaceitApiKeyRepository.class);
    private final FaceitAccountRepository accounts = mock(FaceitAccountRepository.class);
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private FaceitApiKeyRotator keys;
    private FaceitStatsService service;
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
        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of(new FaceitApiKeyEntry("k")));
        keys = new FaceitApiKeyRotator(keyRepository, new SimpleMeterRegistry());
        service = new FaceitStatsService(new EnrichmentCache(repository, properties, MAPPER), client, keys, accounts,
                properties);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        FaceitAccount row = new FaceitAccount(user.getId());
        row.setNickname("Catapult");
        row.setPlayerId(ID);
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));
        when(client.player(ID)).thenReturn(PLAYER);
        when(client.lifetime(ID, "cs2")).thenReturn(LIFETIME);
        when(client.lastMatch(ID, "cs2")).thenReturn(Optional.of(LAST));
    }

    private Optional<FaceitStats> stats(boolean detail) {
        return service.forGame(user, FaceitGame.CS2, FetchBudget.UNLIMITED, detail);
    }

    @Test
    void cs2_givesTheLevelEloLifetimeAndLastMatch_underTheCurrentNickname() {
        FaceitStats stats = stats(false).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.game()).isEqualTo(FaceitGame.CS2);
        assertThat(stats.account()).isEqualTo("CataPult");
        assertThat(stats.more()).isNull();
        FaceitStats.Cs2Stats cs2 = stats.cs2();
        assertThat(cs2.status()).isEqualTo(Status.OK);
        assertThat(cs2.skillLevel()).isEqualTo(8);
        assertThat(cs2.elo()).isEqualTo(1850);
        assertThat(cs2.region()).isEqualTo("EU");
        assertThat(cs2.matches()).isEqualTo(420);
        assertThat(cs2.wins()).isEqualTo(231);
        assertThat(cs2.winRatePercent()).isEqualTo(55);
        assertThat(cs2.averageKd()).isEqualTo(1.12);
        assertThat(cs2.averageHeadshotsPercent()).isEqualTo(48);
        assertThat(cs2.currentWinStreak()).isEqualTo(2);
        assertThat(cs2.longestWinStreak()).isEqualTo(9);
        assertThat(cs2.lastMatch()).isEqualTo(LAST);
        assertThat(cs2.recentResults()).isNull();
    }

    @Test
    void theDetail_addsTheRecentResults() {
        assertThat(stats(true).orElseThrow().cs2().recentResults()).containsExactly(true, false);
    }

    @Test
    void theAnswers_areCachedUnderTheMembersKey() {
        stats(false);
        stats(false);

        verify(client, times(1)).player(ID);
        verify(client, times(1)).lifetime(ID, "cs2");
        verify(client, times(1)).lastMatch(ID, "cs2");
        assertThat(store.keySet()).allMatch(k -> k.contains("|u:" + user.getId() + ":faceit:" + ID));
    }

    @Test
    void aPlayerFaceitNoLongerKnows_isNotFound_andCached() {
        when(client.player(ID)).thenThrow(new FaceitNotFoundException("gone"));

        FaceitStats stats = stats(false).orElseThrow();
        stats(false);

        assertThat(stats.status()).isEqualTo(Status.NOT_FOUND);
        assertThat(stats.cs2().status()).isEqualTo(Status.NOT_FOUND);
        assertThat(stats.account()).isEqualTo("Catapult");
        verify(client, times(1)).player(ID);
        verify(client, never()).lifetime(any(), any());
    }

    @Test
    void aPlayerWithoutCs2_isNotFound() {
        when(client.player(ID)).thenReturn(new Player(ID, "CataPult", null, null, null, null));

        FaceitStats stats = stats(false).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.NOT_FOUND);
        assertThat(stats.cs2().elo()).isNull();
        verify(client, never()).lifetime(any(), any());
    }

    @Test
    void anOutage_isUnavailable_andNotCached() {
        when(client.player(ID)).thenThrow(new FaceitUnavailableException("down"));

        FaceitStats first = stats(false).orElseThrow();
        stats(false);

        assertThat(first.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(first.cs2().status()).isEqualTo(Status.UNAVAILABLE);
        verify(client, times(2)).player(ID);
        assertThat(store).isEmpty();
    }

    @Test
    void aBrokenPart_leavesTheProfileServed_andIsNotCached() {
        when(client.lifetime(ID, "cs2")).thenThrow(new FaceitUnavailableException("down"));
        when(client.lastMatch(ID, "cs2")).thenThrow(new IllegalStateException("garbled"));

        FaceitStats stats = stats(false).orElseThrow();
        stats(false);

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.cs2().elo()).isEqualTo(1850);
        assertThat(stats.cs2().matches()).isNull();
        assertThat(stats.cs2().lastMatch()).isNull();
        verify(client, times(2)).lifetime(ID, "cs2");
        verify(client, times(2)).lastMatch(ID, "cs2");
    }

    @Test
    void noStatsOnTheGame_andNoMatch_areOkWithoutThem() {
        when(client.lifetime(ID, "cs2")).thenThrow(new FaceitNotFoundException("none"));
        when(client.lastMatch(ID, "cs2")).thenReturn(Optional.empty());

        FaceitStats stats = stats(true).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.cs2().matches()).isNull();
        assertThat(stats.cs2().recentResults()).isNull();
        assertThat(stats.cs2().lastMatch()).isNull();
    }

    @Test
    void anEmptyPool_theSourceOff_orNoNickname_giveNoObject() {
        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of());
        keys.refreshKeys();
        assertThat(stats(false)).isEmpty();
        assertThat(service.available()).isFalse();

        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of(new FaceitApiKeyEntry("k")));
        keys.refreshKeys();
        properties.getFaceit().setEnabled(false);
        assertThat(stats(false)).isEmpty();

        properties.getFaceit().setEnabled(true);
        properties.getFaceitPlayer().setEnabled(false);
        assertThat(stats(false)).isEmpty();

        properties.getFaceitPlayer().setEnabled(null);
        when(accounts.findById(user.getId())).thenReturn(Optional.empty());
        assertThat(stats(false)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void aPartTurnedOff_isNotAsked() {
        properties.getFaceitStats().setEnabled(false);
        properties.getFaceitLastMatch().setEnabled(false);

        FaceitStats stats = stats(false).orElseThrow();

        assertThat(stats.cs2().elo()).isEqualTo(1850);
        assertThat(stats.cs2().matches()).isNull();
        verify(client, never()).lifetime(any(), any());
        verify(client, never()).lastMatch(any(), any());
    }

    @Test
    void aSpentBudget_isThrown() {
        assertThatThrownBy(() -> service.forGame(user, FaceitGame.CS2, () -> false, false))
                .isInstanceOf(UpstreamBudgetExceededException.class);
    }

    @Test
    void theGameBeingPlayed_isFoundBySteamAppIdOrIgdbId() {
        DetectedGame cs2 = new DetectedGame("730", GameBinding.SourceType.STEAM, "Counter-Strike 2");
        DetectedGame portal = new DetectedGame("620", GameBinding.SourceType.STEAM, "Portal 2");
        DetectedGame manual = new DetectedGame("x", GameBinding.SourceType.MANUAL, "CS2");

        assertThat(service.forDetected(user, cs2, null, FetchBudget.UNLIMITED)).isPresent();
        assertThat(service.forDetected(user, manual, "242408", FetchBudget.UNLIMITED)).isPresent();
        assertThat(service.forDetected(user, portal, "72", FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void theFaceitSources_arePersonal() {
        assertThat(List.of(EnrichmentSource.FACEIT_PLAYER, EnrichmentSource.FACEIT_STATS,
                EnrichmentSource.FACEIT_LAST_MATCH)).allMatch(EnrichmentSource::personal);
    }
}
