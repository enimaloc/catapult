package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.ExperimentService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatapultExperimentVariantFunctionTest {

    @Test
    void invokeReturnsTheAssignedVariantKey() throws Exception {
        ExperimentService experimentService = mock(ExperimentService.class);
        UserAccount user = new UserAccount();
        ExperimentVariant variant = new ExperimentVariant();
        variant.setKey("treatment");
        when(experimentService.getVariant(user, "my_experiment")).thenReturn(Optional.of(variant));

        CatapultExperimentVariantFunction fn = new CatapultExperimentVariantFunction(experimentService);
        assertThat(fn.namespace()).isEqualTo("catapult");
        assertThat(fn.name()).isEqualTo("experimentVariant");
        assertThat(fn.invoke(user, new Object[]{"my_experiment"})).isEqualTo("treatment");
    }

    @Test
    void invokeReturnsEmptyStringWhenNotAssigned() throws Exception {
        ExperimentService experimentService = mock(ExperimentService.class);
        UserAccount user = new UserAccount();
        when(experimentService.getVariant(user, "unknown")).thenReturn(Optional.empty());

        CatapultExperimentVariantFunction fn = new CatapultExperimentVariantFunction(experimentService);
        assertThat(fn.invoke(user, new Object[]{"unknown"})).isEqualTo("");
    }
}
