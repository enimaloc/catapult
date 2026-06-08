package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.SystemSetting;
import fr.enimaloc.catapult.domain.WhitelistEntry;
import fr.enimaloc.catapult.repository.SystemSettingRepository;
import fr.enimaloc.catapult.repository.WhitelistEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WhitelistService {

    private static final String ENABLED_KEY = "whitelist.enabled";

    private final WhitelistEntryRepository whitelistEntryRepository;
    private final SystemSettingRepository systemSettingRepository;

    public boolean isEnabled() {
        return systemSettingRepository.findById(ENABLED_KEY)
            .map(s -> Boolean.parseBoolean(s.getValue()))
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
        log.info("Whitelist {}", enabled ? "enabled" : "disabled");
    }

    @Transactional
    public void toggle() {
        setEnabled(!isEnabled());
    }

    public boolean contains(String twitchId) {
        return whitelistEntryRepository.existsById(twitchId);
    }

    public void add(String twitchId) {
        if (!whitelistEntryRepository.existsById(twitchId)) {
            whitelistEntryRepository.save(new WhitelistEntry(twitchId));
            log.debug("Added ID to whitelist: {}", twitchId);
        }
    }

    public void remove(String twitchId) {
        whitelistEntryRepository.deleteById(twitchId);
        log.debug("Removed ID from whitelist: {}", twitchId);
    }

    public List<WhitelistEntry> findAll() {
        return whitelistEntryRepository.findAll();
    }
}
