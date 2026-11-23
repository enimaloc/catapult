package fr.enimaloc.catapult.service.supercell;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.SupercellData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.supercell.SupercellAccount;
import fr.enimaloc.catapult.domain.supercell.SupercellApiKeyEntry;
import fr.enimaloc.catapult.getter.supercell.SupercellApiKeyRotator;
import fr.enimaloc.catapult.repository.supercell.SupercellAccountRepository;
import fr.enimaloc.catapult.repository.supercell.SupercellApiKeyRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.MemberFetchBudgets;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellClient;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellGame;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.SupercellPlayer;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellNotFoundException;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellStatsService;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellUnavailableException;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SupercellAccountServiceTest {

    static final SupercellPlayer PLAYER = new SupercellPlayer("#2PP", "Chief", null, null, null);

    private final SupercellAccountRepository accounts = mock(SupercellAccountRepository.class);
    private final SupercellClient client = mock(SupercellClient.class);
    private final SupercellApiKeyRepository keyRepository = mock(SupercellApiKeyRepository.class);
    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final ChannelEventPublisher events = mock(ChannelEventPublisher.class);
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private SupercellApiKeyRotator keys;
    private SupercellAccountService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of(new SupercellApiKeyEntry("k", "clash-of-clans")));
        keys = new SupercellApiKeyRotator(keyRepository, new SimpleMeterRegistry());
        SupercellStatsService stats = new SupercellStatsService(cache, client, keys, accounts, properties);
        service = new SupercellAccountService(accounts, client, stats, cache, new MemberFetchBudgets(properties),
                events, Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC));
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(accounts.findById(user.getId())).thenReturn(Optional.empty());
        when(client.player(SupercellGame.CLASH_OF_CLANS, "#2PP")).thenReturn(PLAYER);
    }

    private SupercellAccount saved() {
        ArgumentCaptor<SupercellAccount> row = ArgumentCaptor.forClass(SupercellAccount.class);
        verify(accounts).save(row.capture());
        return row.getValue();
    }

    private static void assertProblem(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiProblem.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void aTag_isNormalized_checkedWithTheGame_andSaved_andItsPlayerCached() {
        service.save(user, "clash-of-clans", " 2pp ");

        SupercellAccount row = saved();
        assertThat(row.getCocTag()).isEqualTo("#2PP");
        assertThat(row.getCocName()).isEqualTo("Chief");
        assertThat(row.getUpdatedAt()).isEqualTo(Instant.parse("2026-11-23T10:00:00Z"));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(cache);
        order.verify(cache).evictUser(user.getId(), List.of(EnrichmentSource.SUPERCELL_CLASH_OF_CLANS));
        order.verify(cache).put(eq(EnrichmentSource.SUPERCELL_CLASH_OF_CLANS), eq("u:" + user.getId() + ":#2PP"),
                eq(new SupercellStatsService.PlayerCell(PLAYER)));
        ArgumentCaptor<SupercellData> data = ArgumentCaptor.forClass(SupercellData.class);
        verify(events).supercellAccountChanged(eq(user.getId()), data.capture());
        assertThat(data.getValue().clashOfClans().available()).isTrue();
        assertThat(data.getValue().clashRoyale().available()).isFalse();
    }

    @Test
    void theSameTagAgain_keepsWhatWasCached() {
        SupercellAccount row = new SupercellAccount(user.getId());
        row.set(SupercellGame.CLASH_OF_CLANS, "#2PP", "Chief");
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));

        service.save(user, "clash-of-clans", "#2PP");

        verify(cache, never()).evictUser(any(), any());
    }

    @Test
    void aMalformedTag_orAnUnknownGame_isA400_thatCostsNothing() {
        for (int i = 0; i < 30; i++) {
            assertProblem(() -> service.save(user, "clash-of-clans", "#ABC"), ErrorCode.BAD_REQUEST);
        }
        assertProblem(() -> service.save(user, "hay-day", "#2PP"), ErrorCode.BAD_REQUEST);
        assertProblem(() -> service.delete(user, "hay-day"), ErrorCode.BAD_REQUEST);
        verifyNoInteractions(client);
        // the budget is untouched: a real save still goes through
        service.save(user, "clash-of-clans", "#2PP");
    }

    @Test
    void aTagNoPlayerHas_isA400_andAnOutageA503_nothingWritten() {
        when(client.player(SupercellGame.CLASH_OF_CLANS, "#9YY")).thenThrow(new SupercellNotFoundException("x"));
        when(client.player(SupercellGame.CLASH_OF_CLANS, "#8YY")).thenThrow(new SupercellUnavailableException("x"));

        assertProblem(() -> service.save(user, "clash-of-clans", "#9YY"), ErrorCode.BAD_REQUEST);
        assertProblem(() -> service.save(user, "clash-of-clans", "#8YY"), ErrorCode.SERVICE_UNAVAILABLE);
        verify(accounts, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void aGameWithoutKey_orTheSourceOff_isA503_withoutAsking() {
        assertProblem(() -> service.save(user, "brawl-stars", "#2PP"), ErrorCode.SERVICE_UNAVAILABLE);
        properties.getSupercell().setEnabled(false);
        assertProblem(() -> service.save(user, "clash-of-clans", "#2PP"), ErrorCode.SERVICE_UNAVAILABLE);
        verifyNoInteractions(client);
    }

    @Test
    void theChecks_takeTheMembersRefreshes_thenA429() {
        properties.setMemberPerMinute(2);
        SupercellStatsService stats = new SupercellStatsService(cache, client, keys, accounts, properties);
        service = new SupercellAccountService(accounts, client, stats, cache, new MemberFetchBudgets(properties),
                events);

        service.save(user, "clash-of-clans", "#2PP");
        service.save(user, "clash-of-clans", "#2PP");
        assertThatThrownBy(() -> service.save(user, "clash-of-clans", "#2PP"))
                .isInstanceOfSatisfying(ApiProblem.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
                    assertThat(e.getBody().getProperties()).containsKey("retryAfter");
                });
        verify(client, times(2)).player(any(), any());
    }

    @Test
    void delete_forgetsOneGame_andTheRowGoesWithTheLast() {
        SupercellAccount row = new SupercellAccount(user.getId());
        row.set(SupercellGame.CLASH_OF_CLANS, "#2PP", "Chief");
        row.set(SupercellGame.BRAWL_STARS, "#9YY", "Other");
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));

        service.delete(user, "clash-of-clans");

        assertThat(row.getCocTag()).isNull();
        verify(accounts).save(row);
        verify(cache).evictUser(user.getId(), List.of(EnrichmentSource.SUPERCELL_CLASH_OF_CLANS));
        verify(events).supercellAccountChanged(eq(user.getId()), any());

        service.delete(user, "brawl-stars");
        verify(accounts).delete(row);
    }

    @Test
    void delete_withoutARow_writesAndTellsNothing() {
        service.delete(user, "clash-royale");

        verify(accounts, never()).save(any());
        verify(accounts, never()).delete(any());
        verify(cache).evictUser(user.getId(), List.of(EnrichmentSource.SUPERCELL_CLASH_ROYALE));
        verifyNoInteractions(events);
    }

    @Test
    void aWriteCrossingAnother_isRedoneOnce_thenA409() {
        when(accounts.save(any())).thenThrow(new ObjectOptimisticLockingFailureException(SupercellAccount.class, 1))
                .thenAnswer(i -> i.getArgument(0));
        service.save(user, "clash-of-clans", "#2PP");
        verify(accounts, times(2)).save(any());

        when(accounts.save(any())).thenThrow(new ObjectOptimisticLockingFailureException(SupercellAccount.class, 1));
        assertProblem(() -> service.save(user, "clash-of-clans", "#2PP"), ErrorCode.CONFLICT);
    }

    @Test
    void aFailedPush_neverFailsTheSave() {
        doThrow(new IllegalStateException("redis down")).when(events).supercellAccountChanged(any(), any());

        service.save(user, "clash-of-clans", "#2PP");

        verify(accounts).save(any());
    }

    @Test
    void data_isBuiltFromTheRowOnly() {
        SupercellAccount row = new SupercellAccount(user.getId());
        row.set(SupercellGame.CLASH_ROYALE, "#2PP", "Chief");
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));

        SupercellData data = service.data(user);

        assertThat(data.clashRoyale()).isEqualTo(new SupercellData.Game("#2PP", "Chief", false));
        assertThat(data.clashOfClans()).isEqualTo(new SupercellData.Game(null, null, true));
        verifyNoInteractions(client);
    }
}
