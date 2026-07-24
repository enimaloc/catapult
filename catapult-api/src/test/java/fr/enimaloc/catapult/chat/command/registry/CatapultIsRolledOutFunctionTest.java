package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.ExperimentService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatapultIsRolledOutFunctionTest {

    @Test
    void invokeReturnsTrueStringWhenRolledOut() throws Exception {
        ExperimentService experimentService = mock(ExperimentService.class);
        UserAccount user = new UserAccount();
        when(experimentService.evaluateGate(user, "my_gate")).thenReturn(true);

        CatapultIsRolledOutFunction fn = new CatapultIsRolledOutFunction(experimentService);
        assertThat(fn.namespace()).isEqualTo("catapult");
        assertThat(fn.name()).isEqualTo("isRolledOut");
        assertThat(fn.invoke(user, new Object[]{"my_gate"})).isEqualTo("true");
    }

    @Test
    void invokeReturnsFalseStringWhenNotRolledOut() throws Exception {
        ExperimentService experimentService = mock(ExperimentService.class);
        UserAccount user = new UserAccount();
        when(experimentService.evaluateGate(user, "my_gate")).thenReturn(false);

        CatapultIsRolledOutFunction fn = new CatapultIsRolledOutFunction(experimentService);
        assertThat(fn.invoke(user, new Object[]{"my_gate"})).isEqualTo("false");
    }
}
