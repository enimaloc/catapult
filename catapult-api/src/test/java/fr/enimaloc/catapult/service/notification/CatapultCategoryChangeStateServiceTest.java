package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.CatapultCategoryChangeState;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.CatapultCategoryChangeStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatapultCategoryChangeStateServiceTest {

    @Mock private CatapultCategoryChangeStateRepository repository;
    @InjectMocks private CatapultCategoryChangeStateService service;

    private UserAccount user;

    @BeforeEach
    void setup() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void recordCatapultChangeAndReturnPrevious_noPriorState_savesWithNullPreviousAndReturnsEmpty() {
        when(repository.findById(user.getId())).thenReturn(Optional.empty());
        ArgumentCaptor<CatapultCategoryChangeState> captor = ArgumentCaptor.forClass(CatapultCategoryChangeState.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        Optional<String> previous = service.recordCatapultChangeAndReturnPrevious(user, "111");

        assertThat(captor.getValue().getGameId()).isEqualTo("111");
        assertThat(captor.getValue().getPreviousGameId()).isNull();
        assertThat(previous).isEmpty();
    }

    @Test
    void recordCatapultChangeAndReturnPrevious_priorState_shiftsGameIdToPreviousAndReturnsOldValue() {
        CatapultCategoryChangeState existing = new CatapultCategoryChangeState();
        existing.setUser(user);
        existing.setGameId("111");
        when(repository.findById(user.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<String> previous = service.recordCatapultChangeAndReturnPrevious(user, "222");

        assertThat(existing.getGameId()).isEqualTo("222");
        assertThat(existing.getPreviousGameId()).isEqualTo("111");
        assertThat(previous).contains("111");
    }

    @Test
    void consumeIfMatches_matchingGameId_returnsPreviousGameIdAndClearsPendingMarker() {
        CatapultCategoryChangeState existing = pending("222", "111");
        when(repository.findById(user.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<CatapultCategoryChangeStateService.SelfChange> result = service.consumeIfMatches(user, "222");

        assertThat(result).map(CatapultCategoryChangeStateService.SelfChange::previousGameId).contains("111");
        assertThat(existing.getAppliedAt()).isNull();
        // gameId/previousGameId survive: they are the "previous category" the next Catapult change reports.
        assertThat(existing.getGameId()).isEqualTo("222");
        assertThat(existing.getPreviousGameId()).isEqualTo("111");
    }

    @Test
    void consumeIfMatches_secondIdenticalDelivery_isNoLongerTreatedAsSelfSet() {
        CatapultCategoryChangeState existing = pending("222", "111");
        when(repository.findById(user.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.consumeIfMatches(user, "222")).isPresent();
        assertThat(service.consumeIfMatches(user, "222")).isEmpty();
    }

    @Test
    void consumeIfMatches_matchWithNoPreviousCategory_stillCountsAsSelfSet() {
        CatapultCategoryChangeState existing = pending("222", null);
        when(repository.findById(user.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<CatapultCategoryChangeStateService.SelfChange> result = service.consumeIfMatches(user, "222");

        assertThat(result).isPresent();
        assertThat(result.get().previousGameId()).isNull();
    }

    @Test
    void consumeIfMatches_rapidRechange_marksTheLatestCategoryOnly() {
        CatapultCategoryChangeState state = new CatapultCategoryChangeState();
        state.setUser(user);
        when(repository.findById(user.getId())).thenReturn(Optional.of(state));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Catapult: 111 → 222, then 222 → 333 before either channel.update arrives.
        service.recordCatapultChangeAndReturnPrevious(user, "222");
        service.recordCatapultChangeAndReturnPrevious(user, "333");

        // A single marker slot only tracks the latest change; 333 is still recognised as self-set.
        Optional<CatapultCategoryChangeStateService.SelfChange> result = service.consumeIfMatches(user, "333");
        assertThat(result).map(CatapultCategoryChangeStateService.SelfChange::previousGameId).contains("222");
        assertThat(service.consumeIfMatches(user, "333")).isEmpty();
    }

    @Test
    void consumeIfMatches_nonMatchingGameId_returnsEmpty() {
        when(repository.findById(user.getId())).thenReturn(Optional.of(pending("222", "111")));

        assertThat(service.consumeIfMatches(user, "333")).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void consumeIfMatches_noState_returnsEmpty() {
        when(repository.findById(user.getId())).thenReturn(Optional.empty());

        assertThat(service.consumeIfMatches(user, "333")).isEmpty();
    }

    private CatapultCategoryChangeState pending(String gameId, String previousGameId) {
        CatapultCategoryChangeState state = new CatapultCategoryChangeState();
        state.setUser(user);
        state.setGameId(gameId);
        state.setPreviousGameId(previousGameId);
        state.setAppliedAt(Instant.now());
        return state;
    }
}
