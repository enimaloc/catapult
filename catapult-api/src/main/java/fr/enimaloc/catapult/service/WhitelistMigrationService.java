package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.WhitelistEntry;
import fr.enimaloc.catapult.repository.WhitelistEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(1)
public class WhitelistMigrationService implements ApplicationRunner {

    private final WhitelistEntryRepository whitelistEntryRepository;

    @Setter
    @Value("${app.whitelist:}")
    private List<String> configWhitelist = new ArrayList<>();

    @Override
    public void run(ApplicationArguments args) {
        if (configWhitelist.isEmpty()) return;

        int imported = 0;
        for (String twitchId : configWhitelist) {
            if (!whitelistEntryRepository.existsById(twitchId)) {
                whitelistEntryRepository.save(new WhitelistEntry(twitchId));
                imported++;
            }
        }
        if (imported > 0) {
            log.info("Imported {} Twitch ID(s) from app.whitelist into DB", imported);
        }
    }
}
