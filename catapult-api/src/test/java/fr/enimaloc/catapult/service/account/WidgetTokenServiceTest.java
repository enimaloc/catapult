package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WidgetTokenServiceTest {

    @Mock private UserAccountRepository repository;
    @InjectMocks private WidgetTokenService service;

    private UserAccount user;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void getOrCreate_keepsAnExistingToken_withoutWriting() {
        UUID token = UUID.randomUUID();
        user.setWidgetToken(token);

        assertThat(service.getOrCreate(user)).isEqualTo(token);
        verifyNoInteractions(repository);
    }

    @Test
    void getOrCreate_setsTheTokenColumnAlone_neverSavingTheWholeAccount() {
        when(repository.setWidgetTokenIfAbsent(eq(user.getId()), any())).thenReturn(1);

        UUID token = service.getOrCreate(user);

        ArgumentCaptor<UUID> written = ArgumentCaptor.forClass(UUID.class);
        verify(repository).setWidgetTokenIfAbsent(eq(user.getId()), written.capture());
        assertThat(token).isEqualTo(written.getValue()).isEqualTo(user.getWidgetToken());
        verify(repository, never()).save(any());
    }

    @Test
    void getOrCreate_keepsTheTokenAConcurrentRequestSetFirst() {
        UUID theirs = UUID.randomUUID();
        when(repository.setWidgetTokenIfAbsent(eq(user.getId()), any())).thenReturn(0);
        when(repository.findWidgetTokenById(user.getId())).thenReturn(Optional.of(theirs));

        assertThat(service.getOrCreate(user)).isEqualTo(theirs);
        assertThat(user.getWidgetToken()).isEqualTo(theirs);
    }

    @Test
    void regenerate_replacesTheTokenColumnAlone() {
        UUID old = UUID.randomUUID();
        user.setWidgetToken(old);

        UUID token = service.regenerate(user);

        assertThat(token).isNotEqualTo(old).isEqualTo(user.getWidgetToken());
        verify(repository).setWidgetToken(user.getId(), token);
        verify(repository, never()).save(any());
    }
}
