package fr.enimaloc.catapult.chat.command.registry.catapult;

import fr.enimaloc.catapult.domain.ChatCommandSetting;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandSettingRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatapultSetParamFunctionTest {

    @Test
    void invokeUpsertsANewKey() throws Exception {
        ChatCommandSettingRepository repository = mock(ChatCommandSettingRepository.class);
        UserAccount user = new UserAccount();
        when(repository.findByUserAndKey(user, "language")).thenReturn(Optional.empty());

        CatapultSetParamFunction fn = new CatapultSetParamFunction(repository);
        assertThat(fn.namespace()).isEqualTo("catapult");
        assertThat(fn.name()).isEqualTo("setParam");
        assertThat(fn.parameterNames()).containsExactly("key", "value");
        assertThat(fn.isAction()).isTrue();
        assertThat(fn.invoke(user, new Object[]{"language", "fr"})).isEqualTo("");

        org.mockito.ArgumentCaptor<ChatCommandSetting> captor = org.mockito.ArgumentCaptor.forClass(ChatCommandSetting.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getKey()).isEqualTo("language");
        assertThat(captor.getValue().getValue()).isEqualTo("fr");
        assertThat(captor.getValue().getUser()).isEqualTo(user);
    }

    @Test
    void invokeUpdatesAnExistingKey() throws Exception {
        ChatCommandSettingRepository repository = mock(ChatCommandSettingRepository.class);
        UserAccount user = new UserAccount();
        ChatCommandSetting existing = new ChatCommandSetting();
        existing.setUser(user);
        existing.setKey("language");
        existing.setValue("en");
        when(repository.findByUserAndKey(user, "language")).thenReturn(Optional.of(existing));

        CatapultSetParamFunction fn = new CatapultSetParamFunction(repository);
        fn.invoke(user, new Object[]{"language", "fr"});

        assertThat(existing.getValue()).isEqualTo("fr");
        verify(repository).save(existing);
    }

    @Test
    void invokeRejectsAKeyThatDoesNotMatchTheIdentifierPattern() {
        ChatCommandSettingRepository repository = mock(ChatCommandSettingRepository.class);
        UserAccount user = new UserAccount();

        CatapultSetParamFunction fn = new CatapultSetParamFunction(repository);
        assertThatThrownBy(() -> fn.invoke(user, new Object[]{"not-valid!", "fr"}))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invokeRejectsAReservedKey() {
        ChatCommandSettingRepository repository = mock(ChatCommandSettingRepository.class);
        UserAccount user = new UserAccount();

        CatapultSetParamFunction fn = new CatapultSetParamFunction(repository);
        assertThatThrownBy(() -> fn.invoke(user, new Object[]{"__proto__", "x"}))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
