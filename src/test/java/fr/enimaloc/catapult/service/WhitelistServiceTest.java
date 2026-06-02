package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.SystemSetting;
import fr.enimaloc.catapult.domain.WhitelistEntry;
import fr.enimaloc.catapult.repository.SystemSettingRepository;
import fr.enimaloc.catapult.repository.WhitelistEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WhitelistServiceTest {

    @Mock WhitelistEntryRepository whitelistEntryRepository;
    @Mock SystemSettingRepository systemSettingRepository;
    @InjectMocks WhitelistService whitelistService;

    @Test
    void isEnabled_returnsFalse_whenSettingAbsent() {
        when(systemSettingRepository.findById("whitelist.enabled")).thenReturn(Optional.empty());
        assertThat(whitelistService.isEnabled()).isFalse();
    }

    @Test
    void isEnabled_returnsTrue_whenSettingIsTrue() {
        SystemSetting s = new SystemSetting();
        s.setKey("whitelist.enabled");
        s.setValue("true");
        when(systemSettingRepository.findById("whitelist.enabled")).thenReturn(Optional.of(s));
        assertThat(whitelistService.isEnabled()).isTrue();
    }

    @Test
    void isEnabled_returnsFalse_whenSettingIsFalse() {
        SystemSetting s = new SystemSetting();
        s.setKey("whitelist.enabled");
        s.setValue("false");
        when(systemSettingRepository.findById("whitelist.enabled")).thenReturn(Optional.of(s));
        assertThat(whitelistService.isEnabled()).isFalse();
    }

    @Test
    void setEnabled_createsSettingWhenAbsent() {
        when(systemSettingRepository.findById("whitelist.enabled")).thenReturn(Optional.empty());
        whitelistService.setEnabled(true);
        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(systemSettingRepository).save(captor.capture());
        assertThat(captor.getValue().getKey()).isEqualTo("whitelist.enabled");
        assertThat(captor.getValue().getValue()).isEqualTo("true");
    }

    @Test
    void setEnabled_updatesExistingSetting() {
        SystemSetting existing = new SystemSetting();
        existing.setKey("whitelist.enabled");
        existing.setValue("true");
        when(systemSettingRepository.findById("whitelist.enabled")).thenReturn(Optional.of(existing));
        whitelistService.setEnabled(false);
        verify(systemSettingRepository).save(existing);
        assertThat(existing.getValue()).isEqualTo("false");
    }

    @Test
    void contains_delegatesToRepository() {
        when(whitelistEntryRepository.existsById("123")).thenReturn(true);
        assertThat(whitelistService.contains("123")).isTrue();
    }

    @Test
    void add_savesNewEntry_whenNotAlreadyPresent() {
        when(whitelistEntryRepository.existsById("456")).thenReturn(false);
        whitelistService.add("456");
        ArgumentCaptor<WhitelistEntry> captor = ArgumentCaptor.forClass(WhitelistEntry.class);
        verify(whitelistEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getTwitchId()).isEqualTo("456");
    }

    @Test
    void add_doesNotSave_whenAlreadyPresent() {
        when(whitelistEntryRepository.existsById("456")).thenReturn(true);
        whitelistService.add("456");
        verify(whitelistEntryRepository, never()).save(any());
    }

    @Test
    void remove_deletesById() {
        whitelistService.remove("789");
        verify(whitelistEntryRepository).deleteById("789");
    }

    @Test
    void findAll_returnsAllEntries() {
        List<WhitelistEntry> entries = List.of(new WhitelistEntry("111"), new WhitelistEntry("222"));
        when(whitelistEntryRepository.findAll()).thenReturn(entries);
        assertThat(whitelistService.findAll()).isEqualTo(entries);
    }
}
