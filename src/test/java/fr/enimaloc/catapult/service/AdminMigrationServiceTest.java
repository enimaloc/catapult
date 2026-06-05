package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminMigrationServiceTest {

    @Mock private UserSettingsRepository userSettingsRepository;
    @Mock private GetterConfigRepository getterConfigRepository;
    @Mock private GameBindingRepository gameBindingRepository;
    @InjectMocks private AdminMigrationService service;

    private UserAccount user(UUID id) {
        UserAccount u = new UserAccount();
        u.setId(id);
        u.setStatus(UserAccount.Status.ACTIVE);
        return u;
    }

    // ---- copySettings ----

    @Test
    void migrate_settings_noSource_skips() {
        UserAccount source = user(UUID.randomUUID());
        UserAccount target = user(UUID.randomUUID());
        when(userSettingsRepository.findById(source.getId())).thenReturn(Optional.empty());

        service.migrate(source, target, new AdminMigrationService.MigrateOptions(true, false, false));

        verify(userSettingsRepository, never()).save(any());
    }

    @Test
    void migrate_settings_existingTarget_overwritesFields() {
        UserAccount source = user(UUID.randomUUID());
        UserAccount target = user(UUID.randomUUID());

        UserSettings src = new UserSettings();
        src.setUser(source);
        src.setCclFeatureEnabled(false);
        src.setNoGameTwitchGameId("game1");
        src.setNoGameTwitchGameName("Game One");
        src.setNoGameCcls(new HashSet<>(Set.of("gore")));
        src.setIncompleteFallbackTwitchGameId("game2");
        src.setIncompleteFallbackTwitchGameName("Game Two");
        src.setIncompleteFallbackCcls(new HashSet<>(Set.of("language")));
        src.setApplyDefaultOnStreamStart(false);
        src.setApplyDefaultOnNoGame(false);
        src.setApplyDefaultOnStreamEnd(false);

        UserSettings tgt = new UserSettings();
        tgt.setUser(target);

        when(userSettingsRepository.findById(source.getId())).thenReturn(Optional.of(src));
        when(userSettingsRepository.findById(target.getId())).thenReturn(Optional.of(tgt));

        service.migrate(source, target, new AdminMigrationService.MigrateOptions(true, false, false));

        ArgumentCaptor<UserSettings> captor = ArgumentCaptor.forClass(UserSettings.class);
        verify(userSettingsRepository).save(captor.capture());
        UserSettings saved = captor.getValue();
        assertThat(saved.isCclFeatureEnabled()).isFalse();
        assertThat(saved.getNoGameTwitchGameId()).isEqualTo("game1");
        assertThat(saved.getNoGameTwitchGameName()).isEqualTo("Game One");
        assertThat(saved.getNoGameCcls()).containsExactlyInAnyOrder("gore");
        assertThat(saved.getIncompleteFallbackTwitchGameId()).isEqualTo("game2");
        assertThat(saved.getIncompleteFallbackTwitchGameName()).isEqualTo("Game Two");
        assertThat(saved.getIncompleteFallbackCcls()).containsExactlyInAnyOrder("language");
        assertThat(saved.isApplyDefaultOnStreamStart()).isFalse();
        assertThat(saved.isApplyDefaultOnNoGame()).isFalse();
        assertThat(saved.isApplyDefaultOnStreamEnd()).isFalse();
    }

    @Test
    void migrate_settings_noTarget_createsNew() {
        UserAccount source = user(UUID.randomUUID());
        UserAccount target = user(UUID.randomUUID());

        UserSettings src = new UserSettings();
        src.setUser(source);
        src.setNoGameTwitchGameId("game1");
        src.setNoGameTwitchGameName("Game One");
        src.setNoGameCcls(new HashSet<>());
        src.setIncompleteFallbackCcls(new HashSet<>());

        when(userSettingsRepository.findById(source.getId())).thenReturn(Optional.of(src));
        when(userSettingsRepository.findById(target.getId())).thenReturn(Optional.empty());

        service.migrate(source, target, new AdminMigrationService.MigrateOptions(true, false, false));

        ArgumentCaptor<UserSettings> captor = ArgumentCaptor.forClass(UserSettings.class);
        verify(userSettingsRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(target);
        assertThat(captor.getValue().getNoGameTwitchGameId()).isEqualTo("game1");
    }

    // ---- copyGetters ----

    @Test
    void migrate_getters_noConflict_createsNew() {
        UserAccount source = user(UUID.randomUUID());
        UserAccount target = user(UUID.randomUUID());

        GetterConfig gc = new GetterConfig();
        gc.setUser(source);
        gc.setProvider(GetterConfig.Provider.STEAM);
        gc.setPriority(1);
        gc.setEnabled(true);

        when(getterConfigRepository.findByUserOrderByPriorityAsc(source)).thenReturn(List.of(gc));
        when(getterConfigRepository.findByUserAndProvider(target, GetterConfig.Provider.STEAM))
            .thenReturn(Optional.empty());

        service.migrate(source, target, new AdminMigrationService.MigrateOptions(false, true, false));

        ArgumentCaptor<GetterConfig> captor = ArgumentCaptor.forClass(GetterConfig.class);
        verify(getterConfigRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(target);
        assertThat(captor.getValue().getProvider()).isEqualTo(GetterConfig.Provider.STEAM);
        assertThat(captor.getValue().getPriority()).isEqualTo(1);
        assertThat(captor.getValue().isEnabled()).isTrue();
    }

    @Test
    void migrate_getters_conflict_updatesExisting() {
        UserAccount source = user(UUID.randomUUID());
        UserAccount target = user(UUID.randomUUID());

        GetterConfig srcGc = new GetterConfig();
        srcGc.setUser(source);
        srcGc.setProvider(GetterConfig.Provider.STEAM);
        srcGc.setPriority(3);
        srcGc.setEnabled(false);

        GetterConfig tgtGc = new GetterConfig();
        tgtGc.setUser(target);
        tgtGc.setProvider(GetterConfig.Provider.STEAM);
        tgtGc.setPriority(1);
        tgtGc.setEnabled(true);

        when(getterConfigRepository.findByUserOrderByPriorityAsc(source)).thenReturn(List.of(srcGc));
        when(getterConfigRepository.findByUserAndProvider(target, GetterConfig.Provider.STEAM))
            .thenReturn(Optional.of(tgtGc));

        service.migrate(source, target, new AdminMigrationService.MigrateOptions(false, true, false));

        verify(getterConfigRepository).save(tgtGc);
        assertThat(tgtGc.getPriority()).isEqualTo(3);
        assertThat(tgtGc.isEnabled()).isFalse();
    }

    // ---- copyBindings ----

    @Test
    void migrate_bindings_noConflict_createsNew() {
        UserAccount source = user(UUID.randomUUID());
        UserAccount target = user(UUID.randomUUID());

        GameBinding gb = new GameBinding();
        gb.setUser(source);
        gb.setSourceId("12345");
        gb.setSourceType(GameBinding.SourceType.STEAM);
        gb.setSourceName("Half-Life 2");
        gb.setTwitchGameId("t1");
        gb.setTwitchGameName("Half-Life 2");
        gb.setStatus(GameBinding.Status.AUTO);
        gb.setIgnored(false);
        gb.setCclEnabled(true);
        gb.setCcls(new HashSet<>(Set.of("violence")));

        when(gameBindingRepository.findByUser(source)).thenReturn(List.of(gb));
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(
            target, "12345", GameBinding.SourceType.STEAM)).thenReturn(Optional.empty());

        service.migrate(source, target, new AdminMigrationService.MigrateOptions(false, false, true));

        ArgumentCaptor<GameBinding> captor = ArgumentCaptor.forClass(GameBinding.class);
        verify(gameBindingRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(target);
        assertThat(captor.getValue().getSourceId()).isEqualTo("12345");
        assertThat(captor.getValue().getSourceName()).isEqualTo("Half-Life 2");
        assertThat(captor.getValue().getCcls()).containsExactlyInAnyOrder("violence");
    }

    @Test
    void migrate_bindings_conflict_updatesExisting() {
        UserAccount source = user(UUID.randomUUID());
        UserAccount target = user(UUID.randomUUID());

        GameBinding srcGb = new GameBinding();
        srcGb.setUser(source);
        srcGb.setSourceId("12345");
        srcGb.setSourceType(GameBinding.SourceType.STEAM);
        srcGb.setSourceName("Half-Life 2");
        srcGb.setTwitchGameId("t2");
        srcGb.setTwitchGameName("Half-Life 2 Updated");
        srcGb.setStatus(GameBinding.Status.MANUAL);
        srcGb.setIgnored(true);
        srcGb.setCclEnabled(false);
        srcGb.setCcls(new HashSet<>());

        GameBinding tgtGb = new GameBinding();
        tgtGb.setUser(target);
        tgtGb.setSourceId("12345");
        tgtGb.setSourceType(GameBinding.SourceType.STEAM);
        tgtGb.setSourceName("Old Source Name");
        tgtGb.setTwitchGameId("t1");
        tgtGb.setTwitchGameName("Old Name");
        tgtGb.setStatus(GameBinding.Status.AUTO);
        tgtGb.setIgnored(false);
        tgtGb.setCclEnabled(true);
        tgtGb.setCcls(new HashSet<>(Set.of("violence")));

        when(gameBindingRepository.findByUser(source)).thenReturn(List.of(srcGb));
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(
            target, "12345", GameBinding.SourceType.STEAM)).thenReturn(Optional.of(tgtGb));

        service.migrate(source, target, new AdminMigrationService.MigrateOptions(false, false, true));

        verify(gameBindingRepository).save(tgtGb);
        assertThat(tgtGb.getTwitchGameId()).isEqualTo("t2");
        assertThat(tgtGb.getTwitchGameName()).isEqualTo("Half-Life 2 Updated");
        assertThat(tgtGb.getStatus()).isEqualTo(GameBinding.Status.MANUAL);
        assertThat(tgtGb.isIgnored()).isTrue();
        assertThat(tgtGb.isCclEnabled()).isFalse();
        assertThat(tgtGb.getCcls()).isEmpty();
        assertThat(tgtGb.getSourceName()).isEqualTo("Half-Life 2");
    }
}
