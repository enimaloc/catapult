package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.experiment.provider.ActiveProviderHolder;
import fr.enimaloc.catapult.experiment.provider.ExperimentProvider;
import fr.enimaloc.catapult.experiment.provider.ExperimentSummary;
import fr.enimaloc.catapult.repository.ExperimentRepository;
import fr.enimaloc.catapult.repository.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExperimentProviderBootstrapTest {

    @Mock ActiveProviderHolder activeProviderHolder;
    @Mock ExperimentProvider externalProvider;
    @Mock ExperimentRepository experimentRepository;
    @Mock SystemSettingRepository settingRepository;
    @Mock ExperimentProviderProperties properties;

    ExperimentProviderBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = new ExperimentProviderBootstrap(properties, activeProviderHolder, settingRepository, experimentRepository);
    }

    @Test
    void noSyncWhenProviderUnchanged() throws Exception {
        when(properties.getProvider()).thenReturn("gitlab");
        SystemSetting existing = new SystemSetting();
        existing.setKey("experiment.last-provider");
        existing.setValue("gitlab");
        when(settingRepository.findById("experiment.last-provider")).thenReturn(Optional.of(existing));

        bootstrap.run(new DefaultApplicationArguments());

        verify(experimentRepository, never()).findAll();
    }

    @Test
    void syncIsCalledWhenProviderChanges() throws Exception {
        when(properties.getProvider()).thenReturn("gitlab");
        SystemSetting existing = new SystemSetting();
        existing.setKey("experiment.last-provider");
        existing.setValue("internal");
        when(settingRepository.findById("experiment.last-provider")).thenReturn(Optional.of(existing));
        when(activeProviderHolder.get()).thenReturn(externalProvider);
        when(activeProviderHolder.isInternal()).thenReturn(false);

        Experiment exp = new Experiment();
        exp.setKey("k");
        exp.setStatus(Experiment.Status.ACTIVE);
        when(experimentRepository.findAll()).thenReturn(List.of(exp));

        bootstrap.run(new DefaultApplicationArguments());

        verify(externalProvider).importExperiments(List.of(exp));
        verify(settingRepository).save(any(SystemSetting.class));
    }

    @Test
    void lastProviderWrittenOnFirstRun() throws Exception {
        when(properties.getProvider()).thenReturn("internal");
        when(settingRepository.findById("experiment.last-provider")).thenReturn(Optional.empty());

        bootstrap.run(new DefaultApplicationArguments());

        verify(settingRepository).save(argThat(s -> "internal".equals(s.getValue())));
        verify(experimentRepository, never()).findAll();
    }
}
