package fr.enimaloc.catapult.service.enrichment.chess;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding.SourceType;
import fr.enimaloc.catapult.domain.chess.ChessAccount;
import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.chess.ChessAccountRepository;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
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

class ChessStatsServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final ChessRating BLITZ = new ChessRating(3449, 30, null, null, 3503, 46241, 36268, 5575, 4398,
            Instant.ofEpochSecond(1795275975L));
    private static final ChesscomDailyGame MY_MOVE = new ChesscomDailyGame("https://www.chess.com/game/daily/1",
            "white", "rival", true, Instant.ofEpochSecond(1795458668L), "chess");
    private static final ChesscomDailyGame THEIR_MOVE = new ChesscomDailyGame("https://www.chess.com/game/daily/2",
            "black", "other", false, null, "chess");
    private static final LichessUser LICHESS = new LichessUser("drnykterstein", "DrNykterstein", "GM",
            "https://lichess.org/@/DrNykterstein", Map.of("bullet", new ChessRating(3263, 92, false, 23, null, 9585,
            null, null, null, null)), 10455, 7212, 2409, 834, "https://lichess.org/Gkmny9jn/white",
            Instant.ofEpochMilli(1794704009472L));

    private final GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
    private final ChessClient client = mock(ChessClient.class);
    private final ChessAccountRepository accounts = mock(ChessAccountRepository.class);
    private final Map<String, GameEnrichmentCacheEntry> store = new ConcurrentHashMap<>();
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private ChessStatsService service;
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
        service = new ChessStatsService(new EnrichmentCache(repository, properties, MAPPER), client, properties,
                accounts);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(client.chesscomStats("hikaru")).thenReturn(new ChesscomStats(Map.of("blitz", BLITZ), 2814));
        when(client.chesscomGames("hikaru")).thenReturn(List.of(MY_MOVE, THEIR_MOVE));
        when(client.lichessUser("drnykterstein")).thenReturn(LICHESS);
    }

    private void declared(boolean chesscom, boolean lichess) {
        ChessAccount row = new ChessAccount(user.getId());
        if (chesscom) {
            row.setChesscomUsername("hikaru");
            row.setChesscomPlayerId(15448422L);
        }
        if (lichess) {
            row.setLichessId("drnykterstein");
            row.setLichessUsername("DrNykterstein");
        }
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));
    }

    private static DetectedGame chessCategory() {
        return new DetectedGame(null, SourceType.MANUAL, "Chess");
    }

    @Test
    void bothSitesAreReadIntoOneObject() {
        declared(true, true);

        ChessStats stats = service.forDetected(user, chessCategory(), null, FetchBudget.UNLIMITED).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.game()).isEqualTo("chess");
        assertThat(stats.account()).isEqualTo("hikaru");
        assertThat(stats.chesscom().ratings()).containsEntry("blitz", BLITZ);
        assertThat(stats.chesscom().fide()).isEqualTo(2814);
        assertThat(stats.chesscom().dailyGames()).isEqualTo(2);
        assertThat(stats.chesscom().yourTurn()).isEqualTo(1);
        assertThat(stats.chesscom().url()).isEqualTo("https://www.chess.com/member/hikaru");
        assertThat(stats.chesscom().games()).as("detail only").isNull();
        assertThat(stats.lichess().username()).isEqualTo("DrNykterstein");
        assertThat(stats.lichess().title()).isEqualTo("GM");
        assertThat(stats.lichess().playing()).isTrue();
        assertThat(stats.lichess().playingUrl()).isEqualTo("https://lichess.org/Gkmny9jn/white");
        assertThat(stats.lichess().games()).isEqualTo(10455);
        assertThat(stats.more()).isNull();
    }

    @Test
    void theDetailListsTheDailyGames() {
        declared(true, false);

        ChessStats stats = service.forMember(user, FetchBudget.UNLIMITED, true).orElseThrow();

        assertThat(stats.chesscom().games()).containsExactly(MY_MOVE, THEIR_MOVE);
        assertThat(stats.lichess()).isNull();
    }

    @Test
    void anotherGameGivesNothingAndAsksNobody() {
        declared(true, true);

        assertThat(service.forDetected(user, new DetectedGame("440", SourceType.STEAM, "Team Fortress 2"), "123",
                FetchBudget.UNLIMITED)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void theChessSitesIgdbIdsAreChess() {
        declared(false, true);

        assertThat(service.forDetected(user, new DetectedGame("x", SourceType.MANUAL, "Lichess.org"), "122008",
                FetchBudget.UNLIMITED)).isPresent();
    }

    @Test
    void noAccountDeclaredGivesNothing() {
        when(accounts.findById(user.getId())).thenReturn(Optional.empty());

        assertThat(service.forMember(user, FetchBudget.UNLIMITED, false)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void aSourceTurnedOffGivesNoObjectForThatSite() {
        declared(true, true);
        EnrichmentProperties.SourceSettings off = new EnrichmentProperties.SourceSettings();
        off.setEnabled(false);
        properties.setChessLichessUser(off);

        ChessStats stats = service.forMember(user, FetchBudget.UNLIMITED, false).orElseThrow();

        assertThat(stats.lichess()).isNull();
        assertThat(stats.chesscom()).isNotNull();
        verify(client, never()).lichessUser(anyString());
    }

    @Test
    void bothSourcesOffGiveNothing() {
        declared(true, true);
        EnrichmentProperties.SourceSettings off = new EnrichmentProperties.SourceSettings();
        off.setEnabled(false);
        properties.setChessLichessUser(off);
        properties.setChessChesscomStats(off);

        assertThat(service.forMember(user, FetchBudget.UNLIMITED, false)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void anOutageIsUnavailableAndIsNotCached() {
        declared(true, true);
        when(client.chesscomStats("hikaru")).thenThrow(new ChessUnavailableException("down"));
        when(client.lichessUser("drnykterstein")).thenThrow(new IllegalStateException("boom"));

        ChessStats first = service.forMember(user, FetchBudget.UNLIMITED, false).orElseThrow();
        service.forMember(user, FetchBudget.UNLIMITED, false);

        assertThat(first.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(first.chesscom().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(first.chesscom().ratings()).isNull();
        assertThat(first.chesscom().username()).isEqualTo("hikaru");
        assertThat(first.lichess().status()).isEqualTo(Status.UNAVAILABLE);
        verify(client, times(2)).chesscomStats("hikaru");
        verify(client, times(2)).lichessUser("drnykterstein");
    }

    @Test
    void aConfirmedAbsenceIsNotFoundAndIsCached() {
        declared(true, false);
        when(client.chesscomStats("hikaru")).thenThrow(new ChessNotFoundException("renamed"));

        ChessStats first = service.forMember(user, FetchBudget.UNLIMITED, false).orElseThrow();
        ChessStats second = service.forMember(user, FetchBudget.UNLIMITED, false).orElseThrow();

        assertThat(first.status()).isEqualTo(Status.NOT_FOUND);
        assertThat(second.chesscom().status()).isEqualTo(Status.NOT_FOUND);
        verify(client, times(1)).chesscomStats("hikaru");
        verify(client, never()).chesscomGames(anyString());
    }

    @Test
    void aClosedChesscomAccountIsNotFoundAndItsRatingsAreNotRead() {
        declared(true, false);
        when(client.chesscomPlayer("hikaru")).thenThrow(new ChessNotFoundException("closed"));

        ChessStats stats = service.forMember(user, FetchBudget.UNLIMITED, false).orElseThrow();

        assertThat(stats.chesscom().status()).isEqualTo(Status.NOT_FOUND);
        verify(client, never()).chesscomStats(anyString());
    }

    @Test
    void oneSiteReadIsEnoughForAnOkHead() {
        declared(true, true);
        when(client.chesscomStats("hikaru")).thenThrow(new ChessUnavailableException("down"));

        ChessStats stats = service.forMember(user, FetchBudget.UNLIMITED, false).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.chesscom().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(stats.lichess().status()).isEqualTo(Status.OK);
    }

    @Test
    void theGamesFailingKeepsTheRatings() {
        declared(true, false);
        when(client.chesscomGames("hikaru")).thenThrow(new ChessUnavailableException("down"));

        ChessStats stats = service.forMember(user, FetchBudget.UNLIMITED, true).orElseThrow();

        assertThat(stats.chesscom().status()).isEqualTo(Status.OK);
        assertThat(stats.chesscom().ratings()).containsKey("blitz");
        assertThat(stats.chesscom().dailyGames()).isNull();
        assertThat(stats.chesscom().games()).isNull();
    }

    @Test
    void theReadsAreCachedPerMember() {
        declared(true, true);

        service.forMember(user, FetchBudget.UNLIMITED, false);
        service.forMember(user, FetchBudget.UNLIMITED, true);

        verify(client, times(1)).chesscomStats("hikaru");
        verify(client, times(1)).chesscomGames("hikaru");
        verify(client, times(1)).lichessUser("drnykterstein");
        assertThat(store.keySet()).allMatch(k -> k.contains("|u:" + user.getId() + ":"));
    }

    @Test
    void aRefusedBudgetIsRaisedWithoutAskingTheSite() {
        declared(true, false);

        assertThatThrownBy(() -> service.forMember(user, () -> false, true))
                .isInstanceOf(UpstreamBudgetExceededException.class);
        verifyNoInteractions(client);
    }
}
