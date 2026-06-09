package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.getter.SteamApiKeyRotator;
import fr.enimaloc.catapult.repository.SteamApiKeyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SteamApiKeyMigrationServiceTest {

    @Mock SteamApiKeyRepository repository;
    @Mock SteamApiKeyRotator rotator;
    @Mock ApplicationArguments applicationArguments;
    @InjectMocks SteamApiKeyMigrationService service;

    @Test
    void run_importsNewKeys_fromApiKeysList() throws Exception {
        service.setApiKeys(List.of("KEY1", "KEY2"));
        when(repository.existsById("KEY1")).thenReturn(false);
        when(repository.existsById("KEY2")).thenReturn(false);

        service.run(applicationArguments);

        ArgumentCaptor<fr.enimaloc.catapult.domain.SteamApiKeyEntry> captor =
            ArgumentCaptor.forClass(fr.enimaloc.catapult.domain.SteamApiKeyEntry.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting("apiKey")
            .containsExactlyInAnyOrder("KEY1", "KEY2");
    }

    @Test
    void run_importsLegacyKey() throws Exception {
        service.setLegacyApiKey("LEGACY_KEY");
        when(repository.existsById("LEGACY_KEY")).thenReturn(false);

        service.run(applicationArguments);

        ArgumentCaptor<fr.enimaloc.catapult.domain.SteamApiKeyEntry> captor =
            ArgumentCaptor.forClass(fr.enimaloc.catapult.domain.SteamApiKeyEntry.class);
        verify(repository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getApiKey()).isEqualTo("LEGACY_KEY");
    }

    @Test
    void run_deduplicatesLegacyKeyAlreadyInList() throws Exception {
        service.setApiKeys(List.of("KEY1", "LEGACY_KEY"));
        service.setLegacyApiKey("LEGACY_KEY");
        when(repository.existsById(any())).thenReturn(false);

        service.run(applicationArguments);

        ArgumentCaptor<fr.enimaloc.catapult.domain.SteamApiKeyEntry> captor =
            ArgumentCaptor.forClass(fr.enimaloc.catapult.domain.SteamApiKeyEntry.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting("apiKey")
            .containsExactlyInAnyOrder("KEY1", "LEGACY_KEY");
    }

    @Test
    void run_skipsAlreadyPresentKeys() throws Exception {
        service.setApiKeys(List.of("KEY1"));
        when(repository.existsById("KEY1")).thenReturn(true);

        service.run(applicationArguments);

        verify(repository, never()).save(any());
    }

    @Test
    void run_doesNothing_whenNoKeys() throws Exception {
        service.run(applicationArguments);
        verify(repository, never()).save(any());
    }

    @Test
    void run_refreshesRotator_afterImport() throws Exception {
        service.setApiKeys(List.of("KEY1"));
        when(repository.existsById("KEY1")).thenReturn(false);

        service.run(applicationArguments);

        verify(rotator).refreshKeys();
    }

    @Test
    void run_doesNotRefreshRotator_whenNothingImported() throws Exception {
        service.run(applicationArguments);
        verify(rotator, never()).refreshKeys();
    }
}
