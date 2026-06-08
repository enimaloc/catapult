package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.SystemSetting;
import fr.enimaloc.catapult.experiment.provider.ActiveProviderHolder;
import fr.enimaloc.catapult.experiment.provider.ExperimentProvider;
import fr.enimaloc.catapult.repository.ExperimentRepository;
import fr.enimaloc.catapult.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(Integer.MAX_VALUE)
public class ExperimentProviderBootstrap implements ApplicationRunner {

    private static final String LAST_PROVIDER_KEY = "experiment.last-provider";

    private final ExperimentProviderProperties properties;
    private final ActiveProviderHolder activeProviderHolder;
    private final SystemSettingRepository settingRepository;
    private final ExperimentRepository experimentRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String currentType = properties.getProvider();
        String lastType = settingRepository.findById(LAST_PROVIDER_KEY)
            .map(SystemSetting::getValue)
            .orElse(null);

        if (currentType.equals(lastType)) {
            log.info("[Provider] Active experiment provider: {}", currentType);
            return;
        }

        if (lastType != null && !activeProviderHolder.isInternal()) {
            log.info("[Provider] Provider changed {} → {}, syncing experiments...", lastType, currentType);
            syncActiveExperiments();
        }

        SystemSetting setting = settingRepository.findById(LAST_PROVIDER_KEY).orElse(new SystemSetting());
        setting.setKey(LAST_PROVIDER_KEY);
        setting.setValue(currentType);
        settingRepository.save(setting);
        log.info("[Provider] Experiment provider registered: {}", currentType);
    }

    private void syncActiveExperiments() {
        ExperimentProvider provider = activeProviderHolder.get();
        List<Experiment> toSync = experimentRepository.findAll().stream()
            .filter(e -> e.getStatus() == Experiment.Status.ACTIVE || e.getStatus() == Experiment.Status.PAUSED)
            .toList();

        int errors = 0;
        for (Experiment exp : toSync) {
            try {
                provider.importExperiments(List.of(exp));
            } catch (Exception e) {
                log.warn("[Provider] Failed to import experiment '{}': {}", exp.getKey(), e.getMessage());
                errors++;
            }
        }
        log.info("[Provider] Sync complete: {}/{} experiments imported", toSync.size() - errors, toSync.size());
    }
}
