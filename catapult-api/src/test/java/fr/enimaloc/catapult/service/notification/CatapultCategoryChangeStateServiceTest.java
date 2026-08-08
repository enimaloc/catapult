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
    void matchesCatapultChange_matchingGameId_returnsPreviousGameId() {
        CatapultCategoryChangeState existing = new CatapultCategoryChangeState();
        existing.setGameId("222");
        existing.setPreviousGameId("111");
        when(repository.findById(user.getId())).thenReturn(Optional.of(existing));

        Optional<String> result = service.matchesCatapultChange(user, "222");

        assertThat(result).contains("111");
    }

    @Test
    void matchesCatapultChange_nonMatchingGameId_returnsEmpty() {
        CatapultCategoryChangeState existing = new CatapultCategoryChangeState();
        existing.setGameId("222");
        existing.setPreviousGameId("111");
        when(repository.findById(user.getId())).thenReturn(Optional.of(existing));

        Optional<String> result = service.matchesCatapultChange(user, "333");

        assertThat(result).isEmpty();
    }

    @Test
    void matchesCatapultChange_noState_returnsEmpty() {
        when(repository.findById(user.getId())).thenReturn(Optional.empty());

        assertThat(service.matchesCatapultChange(user, "333")).isEmpty();
    }
}
