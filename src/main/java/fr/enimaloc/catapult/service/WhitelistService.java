package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.SystemSetting;
import fr.enimaloc.catapult.domain.WhitelistEntry;
import fr.enimaloc.catapult.repository.SystemSettingRepository;
import fr.enimaloc.catapult.repository.WhitelistEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WhitelistService {

    private static final String ENABLED_KEY = "whitelist.enabled";

    private final WhitelistEntryRepository whitelistEntryRepository;
    private final SystemSettingRepository systemSettingRepository;

    public boolean isEnabled() {
        return systemSettingRepository.findById(ENABLED_KEY)
            .map(s -> "true".equals(s.getValue()))
            .orElse(false);
    }

    public void setEnabled(boolean enabled) {
        SystemSetting setting = systemSettingRepository.findById(ENABLED_KEY)
            .orElseGet(() -> {
                SystemSetting s = new SystemSetting();
                s.setKey(ENABLED_KEY);
                return s;
            });
        setting.setValue(String.valueOf(enabled));
        systemSettingRepository.save(setting);
    }

    public boolean contains(String twitchId) {
        return whitelistEntryRepository.existsById(twitchId);
    }

    public void add(String twitchId) {
        if (!whitelistEntryRepository.existsById(twitchId)) {
            whitelistEntryRepository.save(new WhitelistEntry(twitchId));
        }
    }

    public void remove(String twitchId) {
        whitelistEntryRepository.deleteById(twitchId);
    }

    public List<WhitelistEntry> findAll() {
        return whitelistEntryRepository.findAll();
    }
}
