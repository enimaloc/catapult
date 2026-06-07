package fr.enimaloc.catapult;

import fr.enimaloc.catapult.domain.GetterConfig;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.repository.GetterConfigRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SystemAccountInitializerTest {

    @Mock private UserAccountRepository userAccountRepository;
    @Mock private UserSettingsRepository userSettingsRepository;
    @Mock private GetterConfigRepository getterConfigRepository;
    @Mock private ApplicationArguments args;
    @InjectMocks private SystemAccountInitializer initializer;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(initializer, "appName", "Catapult");
        ReflectionTestUtils.setField(initializer, "defaultNoGameId", "509658");
        ReflectionTestUtils.setField(initializer, "defaultNoGameName", "Just Chatting");
        ReflectionTestUtils.setField(initializer, "defaultIncompleteGameId", "66082");
        ReflectionTestUtils.setField(initializer, "defaultIncompleteGameName", "Games + Demos");
    }

    @Test
    void run_noExistingSystemAccount_createsOne() throws Exception {
        when(userAccountRepository.findBySystemAccountTrue()).thenReturn(Optional.empty());
        ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
        UserAccount saved = new UserAccount();
        saved.setId(UUID.randomUUID());
        saved.setSystemAccount(true);
        saved.setTwitchUsername("Catapult");
        when(userAccountRepository.save(any(UserAccount.class))).thenReturn(saved);

        initializer.run(args);

        verify(userAccountRepository).save(accountCaptor.capture());
        UserAccount created = accountCaptor.getValue();
        assertThat(created.isSystemAccount()).isTrue();
        assertThat(created.getTwitchUsername()).isEqualTo("Catapult");
        assertThat(created.getTwitchId()).isNull();
        assertThat(created.isBotEnabled()).isFalse();
        assertThat(created.getStatus()).isEqualTo(UserAccount.Status.ACTIVE);

        verify(userSettingsRepository).save(any(UserSettings.class));
        verify(getterConfigRepository, times(GetterConfig.Provider.values().length))
            .save(any(GetterConfig.class));
    }

    @Test
    void run_existingSystemAccount_skipsCreation() throws Exception {
        UserAccount existing = new UserAccount();
        existing.setId(UUID.randomUUID());
        existing.setSystemAccount(true);
        when(userAccountRepository.findBySystemAccountTrue()).thenReturn(Optional.of(existing));

        initializer.run(args);

        verify(userAccountRepository, never()).save(any());
        verify(userSettingsRepository, never()).save(any());
        verify(getterConfigRepository, never()).save(any());
    }
}
