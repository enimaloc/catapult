package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.repository.WhitelistEntryRepository;
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
class WhitelistMigrationServiceTest {

    @Mock WhitelistEntryRepository whitelistEntryRepository;
    @Mock ApplicationArguments applicationArguments;
    @InjectMocks WhitelistMigrationService migrationService;

    @Test
    void run_importsNewIds_fromConfigList() throws Exception {
        migrationService.setConfigWhitelist(List.of("111", "222"));
        when(whitelistEntryRepository.existsById("111")).thenReturn(false);
        when(whitelistEntryRepository.existsById("222")).thenReturn(false);

        migrationService.run(applicationArguments);

        ArgumentCaptor<fr.enimaloc.catapult.domain.WhitelistEntry> captor =
            ArgumentCaptor.forClass(fr.enimaloc.catapult.domain.WhitelistEntry.class);
        verify(whitelistEntryRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting("twitchId")
            .containsExactlyInAnyOrder("111", "222");
    }

    @Test
    void run_skipsAlreadyPresentIds() throws Exception {
        migrationService.setConfigWhitelist(List.of("111", "222"));
        when(whitelistEntryRepository.existsById("111")).thenReturn(true);
        when(whitelistEntryRepository.existsById("222")).thenReturn(false);

        migrationService.run(applicationArguments);

        ArgumentCaptor<fr.enimaloc.catapult.domain.WhitelistEntry> captor =
            ArgumentCaptor.forClass(fr.enimaloc.catapult.domain.WhitelistEntry.class);
        verify(whitelistEntryRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getTwitchId()).isEqualTo("222");
    }

    @Test
    void run_doesNothing_whenConfigListEmpty() throws Exception {
        migrationService.setConfigWhitelist(List.of());
        migrationService.run(applicationArguments);
        verify(whitelistEntryRepository, never()).save(any());
    }
}
