package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserFlag;
import fr.enimaloc.catapult.repository.UserFlagRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatapultFlagFunctionTest {

    @Test
    void invokeReturnsTheFlagValueWhenSet() throws Exception {
        UserFlagRepository repository = mock(UserFlagRepository.class);
        UserAccount user = new UserAccount();
        UserFlag flag = new UserFlag();
        flag.setFlagKey("beta_tester");
        flag.setFlagValue("true");
        when(repository.findByUserAndFlagKey(user, "beta_tester")).thenReturn(Optional.of(flag));

        CatapultFlagFunction fn = new CatapultFlagFunction(repository);
        assertThat(fn.namespace()).isEqualTo("catapult");
        assertThat(fn.name()).isEqualTo("flag");
        assertThat(fn.parameterNames()).containsExactly("key");
        assertThat(fn.invoke(user, new Object[]{"beta_tester"})).isEqualTo("true");
    }

    @Test
    void invokeReturnsEmptyStringWhenFlagNotSet() throws Exception {
        UserFlagRepository repository = mock(UserFlagRepository.class);
        UserAccount user = new UserAccount();
        when(repository.findByUserAndFlagKey(user, "unknown")).thenReturn(Optional.empty());

        CatapultFlagFunction fn = new CatapultFlagFunction(repository);
        assertThat(fn.invoke(user, new Object[]{"unknown"})).isEqualTo("");
    }
}
