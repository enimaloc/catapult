package fr.enimaloc.catapult.service.config;

import fr.enimaloc.catapult.config.DatabaseOverridePropertySource;
import fr.enimaloc.catapult.domain.ConfigOverride;
import fr.enimaloc.catapult.repository.ConfigOverrideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigOverrideBootstrap {
    private final ConfigOverrideRepository repo;
    private final DatabaseOverridePropertySource source;
    private final ContextRefresher refresher;

    @EventListener
    public void onReady(ApplicationReadyEvent ignored) {
        loadAll();
        if (refresher != null) {
            refresher.refresh();
        }
    }

    public void loadAll() {
        repo.findByIdModule(ConfigOverride.DEFAULT_MODULE)
                .forEach(o -> source.put(o.getKey(), o.getValue()));
        log.info("Loaded {} config overrides from DB", source.keys().size());
    }
}
