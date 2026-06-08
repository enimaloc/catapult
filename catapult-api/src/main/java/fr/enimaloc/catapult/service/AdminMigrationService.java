package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMigrationService {

    private final UserSettingsRepository userSettingsRepository;
    private final GetterConfigRepository getterConfigRepository;
    private final GameBindingRepository gameBindingRepository;

    public record MigrateOptions(boolean settings, boolean getters, boolean bindings) {}

    @Transactional
    public void migrate(UserAccount source, UserAccount target, MigrateOptions opts) {
        if (opts.settings()) copySettings(source, target);
        if (opts.getters())  copyGetters(source, target);
        if (opts.bindings()) copyBindings(source, target);
        log.info("Migrated data from {} to {} — options: {}", source.getId(), target.getId(), opts);
    }

    private void copySettings(UserAccount source, UserAccount target) {
        UserSettings src = userSettingsRepository.findById(source.getId()).orElse(null);
        if (src == null) return;
        UserSettings tgt = userSettingsRepository.findById(target.getId()).orElseGet(() -> {
            UserSettings s = new UserSettings();
            s.setUser(target);
            return s;
        });
        tgt.setCclFeatureEnabled(src.isCclFeatureEnabled());
        tgt.setNoGameTwitchGameId(src.getNoGameTwitchGameId());
        tgt.setNoGameTwitchGameName(src.getNoGameTwitchGameName());
        tgt.setNoGameCcls(new HashSet<>(src.getNoGameCcls()));
        tgt.setIncompleteFallbackTwitchGameId(src.getIncompleteFallbackTwitchGameId());
        tgt.setIncompleteFallbackTwitchGameName(src.getIncompleteFallbackTwitchGameName());
        tgt.setIncompleteFallbackCcls(new HashSet<>(src.getIncompleteFallbackCcls()));
        tgt.setApplyDefaultOnStreamStart(src.isApplyDefaultOnStreamStart());
        tgt.setApplyDefaultOnNoGame(src.isApplyDefaultOnNoGame());
        tgt.setApplyDefaultOnStreamEnd(src.isApplyDefaultOnStreamEnd());
        userSettingsRepository.save(tgt);
    }

    private void copyGetters(UserAccount source, UserAccount target) {
        List<GetterConfig> sourceGetters = getterConfigRepository.findByUserOrderByPriorityAsc(source);
        for (GetterConfig src : sourceGetters) {
            getterConfigRepository.findByUserAndProvider(target, src.getProvider())
                .ifPresentOrElse(
                    tgt -> {
                        tgt.setPriority(src.getPriority());
                        tgt.setEnabled(src.isEnabled());
                        getterConfigRepository.save(tgt);
                    },
                    () -> {
                        GetterConfig tgt = new GetterConfig();
                        tgt.setUser(target);
                        tgt.setProvider(src.getProvider());
                        tgt.setPriority(src.getPriority());
                        tgt.setEnabled(src.isEnabled());
                        getterConfigRepository.save(tgt);
                    }
                );
        }
    }

    private void copyBindings(UserAccount source, UserAccount target) {
        List<GameBinding> sourceBindings = gameBindingRepository.findByUser(source);
        for (GameBinding src : sourceBindings) {
            gameBindingRepository.findByUserAndSourceIdAndSourceType(
                    target, src.getSourceId(), src.getSourceType())
                .ifPresentOrElse(
                    tgt -> {
                        tgt.setTwitchGameId(src.getTwitchGameId());
                        tgt.setTwitchGameName(src.getTwitchGameName());
                        tgt.setSourceName(src.getSourceName());
                        tgt.setStatus(src.getStatus());
                        tgt.setIgnored(src.isIgnored());
                        tgt.setCclEnabled(src.isCclEnabled());
                        tgt.setCcls(new HashSet<>(src.getCcls()));
                        gameBindingRepository.save(tgt);
                    },
                    () -> {
                        GameBinding tgt = new GameBinding();
                        tgt.setUser(target);
                        tgt.setSourceId(src.getSourceId());
                        tgt.setSourceType(src.getSourceType());
                        tgt.setSourceName(src.getSourceName());
                        tgt.setTwitchGameId(src.getTwitchGameId());
                        tgt.setTwitchGameName(src.getTwitchGameName());
                        tgt.setStatus(src.getStatus());
                        tgt.setIgnored(src.isIgnored());
                        tgt.setCclEnabled(src.isCclEnabled());
                        tgt.setCcls(new HashSet<>(src.getCcls()));
                        gameBindingRepository.save(tgt);
                    }
                );
        }
    }
}
