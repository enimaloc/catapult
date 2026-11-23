package fr.enimaloc.catapult.service.chess;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.ChessData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.chess.ChessAccount;
import fr.enimaloc.catapult.repository.chess.ChessAccountRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.MemberFetchBudgets;
import fr.enimaloc.catapult.service.enrichment.chess.ChessClient;
import fr.enimaloc.catapult.service.enrichment.chess.ChessNotFoundException;
import fr.enimaloc.catapult.service.enrichment.chess.ChessUnavailableException;
import fr.enimaloc.catapult.service.enrichment.chess.ChesscomPlayer;
import fr.enimaloc.catapult.service.enrichment.chess.LichessUser;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChessAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private static final ChesscomPlayer HIKARU = new ChesscomPlayer(15448422L, "hikaru",
            "https://www.chess.com/member/Hikaru", "GM", null);
    private static final LichessUser MAGNUS = new LichessUser("drnykterstein", "DrNykterstein", "GM",
            "https://lichess.org/@/DrNykterstein", Map.of(), 1, 1, 0, 0, null, null);

    private final ChessAccountRepository accounts = mock(ChessAccountRepository.class);
    private final ChessClient client = mock(ChessClient.class);
    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final ChannelEventPublisher events = mock(ChannelEventPublisher.class);
    private final MemberFetchBudgets budgets = mock(MemberFetchBudgets.class);
    private boolean budgetLeft = true;
    private final UserAccount user = new UserAccount();
    private ChessAccountService service;

    @BeforeEach
    void setUp() {
        user.setId(UUID.randomUUID());
        when(accounts.findById(user.getId())).thenReturn(Optional.empty());
        when(budgets.forMember(user.getId())).thenReturn(new FetchBudget() {
            @Override
            public boolean tryAcquire() {
                return budgetLeft;
            }

            @Override
            public Duration retryAfter() {
                return Duration.ofSeconds(42);
            }
        });
        service = new ChessAccountService(accounts, client, cache, events, budgets,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ChessAccount existing(boolean chesscom, boolean lichess) {
        ChessAccount row = new ChessAccount(user.getId());
        if (chesscom) {
            row.setChesscomUsername("hikaru");
            row.setChesscomPlayerId(15448422L);
        }
        if (lichess) {
            row.setLichessId("drnykterstein");
            row.setLichessUsername("DrNykterstein");
        }
        row.setUpdatedAt(NOW.minusSeconds(3600));
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    void aChesscomNameIsCheckedThenStoredWithItsStableId() {
        when(client.chesscomPlayer("Hikaru")).thenReturn(HIKARU);

        service.saveChesscom(user, "  Hikaru ");

        ArgumentCaptor<ChessAccount> saved = ArgumentCaptor.forClass(ChessAccount.class);
        verify(accounts).save(saved.capture());
        assertThat(saved.getValue().getChesscomUsername()).isEqualTo("hikaru");
        assertThat(saved.getValue().getChesscomPlayerId()).isEqualTo(15448422L);
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(NOW);
        verify(cache).evictUser(user.getId(), ChessAccountService.CHESS_SOURCES);
        verify(events).chessAccountChanged(eq(user.getId()), any());
    }

    @Test
    void aLichessNameIsCheckedThenStoredWithItsIdNextToTheChesscomOne() {
        ChessAccount row = existing(true, false);
        when(client.lichessUser("drnykterstein")).thenReturn(MAGNUS);

        service.saveLichess(user, "drnykterstein");

        verify(accounts).save(row);
        assertThat(row.getLichessId()).isEqualTo("drnykterstein");
        assertThat(row.getLichessUsername()).isEqualTo("DrNykterstein");
        assertThat(row.getChesscomUsername()).isEqualTo("hikaru");
        verify(cache).evictUser(user.getId(), ChessAccountService.CHESS_SOURCES);
    }

    @Test
    void aMalformedNameIsRefusedWithoutAskingTheSite() {
        for (String bad : new String[]{null, "", "ab", "a b", "a/b", "x".repeat(26), "élo"}) {
            assertThatThrownBy(() -> service.saveChesscom(user, bad)).isInstanceOf(ApiProblem.class)
                    .extracting(e -> ((ApiProblem) e).code()).isEqualTo(ErrorCode.BAD_REQUEST);
        }
        for (String bad : new String[]{null, "", "a", "-ab", "a b", "a/b", "x".repeat(31)}) {
            assertThatThrownBy(() -> service.saveLichess(user, bad)).isInstanceOf(ApiProblem.class)
                    .extracting(e -> ((ApiProblem) e).code()).isEqualTo(ErrorCode.BAD_REQUEST);
        }
        verifyNoInteractions(client);
        verify(accounts, never()).save(any());
    }

    @Test
    void aSaveWithItsRefreshesSpentIsA429AndAsksNobody() {
        budgetLeft = false;

        assertThatThrownBy(() -> service.saveChesscom(user, "hikaru")).isInstanceOf(ApiProblem.class)
                .satisfies(e -> {
                    assertThat(((ApiProblem) e).code()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
                    assertThat(((ApiProblem) e).getBody().getProperties()).containsEntry("retryAfter", 42L);
                });
        assertThatThrownBy(() -> service.saveLichess(user, "DrNykterstein")).isInstanceOf(ApiProblem.class)
                .extracting(e -> ((ApiProblem) e).code()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        verifyNoInteractions(client);
        verify(accounts, never()).save(any());
    }

    @Test
    void aMalformedNameCostsNoRefresh() {
        assertThatThrownBy(() -> service.saveChesscom(user, "a b")).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> service.saveLichess(user, "-x")).isInstanceOf(ApiProblem.class);

        verifyNoInteractions(budgets);
    }

    @Test
    void anUnknownNameIsA400AndNothingIsWritten() {
        when(client.chesscomPlayer(anyString())).thenThrow(new ChessNotFoundException("no"));
        when(client.lichessUser(anyString())).thenThrow(new ChessNotFoundException("closed"));

        assertThatThrownBy(() -> service.saveChesscom(user, "nobody")).isInstanceOf(ApiProblem.class)
                .extracting(e -> ((ApiProblem) e).code()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThatThrownBy(() -> service.saveLichess(user, "nobody")).isInstanceOf(ApiProblem.class)
                .extracting(e -> ((ApiProblem) e).code()).isEqualTo(ErrorCode.BAD_REQUEST);
        verify(accounts, never()).save(any());
        verifyNoInteractions(cache, events);
    }

    @Test
    void aSiteOutOfReachIsA503AndNothingIsWritten() {
        when(client.chesscomPlayer(anyString())).thenThrow(new ChessUnavailableException("down"));
        when(client.lichessUser(anyString())).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.saveChesscom(user, "hikaru")).isInstanceOf(ApiProblem.class)
                .extracting(e -> ((ApiProblem) e).code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        assertThatThrownBy(() -> service.saveLichess(user, "hikaru")).isInstanceOf(ApiProblem.class)
                .extracting(e -> ((ApiProblem) e).code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        verify(accounts, never()).save(any());
    }

    @Test
    void removingOneSiteKeepsTheOther() {
        ChessAccount row = existing(true, true);

        service.deleteChesscom(user);

        verify(accounts).save(row);
        assertThat(row.getChesscomUsername()).isNull();
        assertThat(row.getChesscomPlayerId()).isNull();
        assertThat(row.getLichessId()).isEqualTo("drnykterstein");
        verify(cache).evictUser(user.getId(), ChessAccountService.CHESS_SOURCES);
        verify(events).chessAccountChanged(eq(user.getId()), any());
    }

    @Test
    void removingTheLastSiteDeletesTheRow() {
        ChessAccount row = existing(false, true);

        service.deleteLichess(user);

        verify(accounts).delete(row);
        verify(accounts, never()).save(any());
        verify(cache).evictUser(user.getId(), ChessAccountService.CHESS_SOURCES);
    }

    @Test
    void removingWhatIsNotThereChangesNothing() {
        existing(false, true);

        service.deleteChesscom(user);

        verify(accounts, never()).save(any());
        verify(accounts, never()).delete(any());
        verifyNoInteractions(cache, events);
    }

    @Test
    void removingWithoutARowChangesNothing() {
        service.deleteLichess(user);

        verify(accounts, never()).delete(any());
        verifyNoInteractions(cache, events);
    }

    @Test
    void aWriteThatCrossesAnotherIsRedoneOnceOnTheFreshRow() {
        when(client.chesscomPlayer("hikaru")).thenReturn(HIKARU);
        when(accounts.save(any())).thenThrow(new OptimisticLockingFailureException("stale"))
                .thenAnswer(i -> i.getArgument(0));

        service.saveChesscom(user, "hikaru");

        verify(accounts, times(2)).save(any());
        verify(events).chessAccountChanged(eq(user.getId()), any());
    }

    @Test
    void aWriteThatCrossesTwiceIsA409() {
        when(client.chesscomPlayer("hikaru")).thenReturn(HIKARU);
        when(accounts.save(any())).thenThrow(new OptimisticLockingFailureException("stale"));

        assertThatThrownBy(() -> service.saveChesscom(user, "hikaru")).isInstanceOf(ApiProblem.class)
                .extracting(e -> ((ApiProblem) e).code()).isEqualTo(ErrorCode.CONFLICT);
        verifyNoInteractions(events);
    }

    @Test
    void aFailedPushNeverFailsTheWrite() {
        when(client.chesscomPlayer("hikaru")).thenReturn(HIKARU);
        doThrow(new IllegalStateException("redis down")).when(events).chessAccountChanged(any(), any());

        service.saveChesscom(user, "hikaru");

        verify(accounts).save(any());
    }

    @Test
    void theCardDataGivesTheNamesAndProfilePagesOnly() {
        existing(true, true);

        assertThat(service.data(user)).isEqualTo(new ChessData("hikaru", "DrNykterstein"));
        verifyNoInteractions(client);
    }

    @Test
    void theCardDataWithoutAccountIsEmpty() {
        assertThat(service.data(user)).isEqualTo(new ChessData(null, null));
    }
}
