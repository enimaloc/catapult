package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatCommandPresetCatalogTest {

    @Mock MessageSource messageSource;
    @Mock ChatCommandDefinitionRepository repository;
    @InjectMocks ChatCommandPresetCatalog catalog;

    private UserAccount user;

    @BeforeEach
    void setup() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(repository.existsByUserAndName(any(), any())).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void instantiate_game_in_french() {
        when(messageSource.getMessage(eq("chat.preset.game.name"), any(), eq(Locale.FRANCE)))
            .thenReturn("!game");
        when(messageSource.getMessage(eq("chat.preset.game.template"), any(), eq(Locale.FRANCE)))
            .thenReturn("Je joue à {game.name}");

        ChatCommandDefinition def = catalog.instantiate(user, "game", Locale.FRANCE);

        assertThat(def.getName()).isEqualTo("!game");
        assertThat(def.getTemplate()).isEqualTo("Je joue à {game.name}");
        assertThat(def.getPresetKey()).isEqualTo("game");
        assertThat(def.getPermission()).isEqualTo(ChatCommandEvent.SenderRole.EVERYONE);
        assertThat(def.isEnabled()).isTrue();
        assertThat(def.getUser()).isSameAs(user);
    }

    @Test
    void unknown_preset_throws_illegal_argument() {
        assertThatThrownBy(() -> catalog.instantiate(user, "unknown-preset", Locale.FRANCE))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void already_instantiated_throws_illegal_state() {
        when(repository.existsByUserAndName(any(), eq("!game"))).thenReturn(true);
        when(messageSource.getMessage(eq("chat.preset.game.name"), any(), eq(Locale.FRANCE)))
            .thenReturn("!game");
        when(messageSource.getMessage(eq("chat.preset.game.template"), any(), eq(Locale.FRANCE)))
            .thenReturn("Je joue à {game.name}");

        assertThatThrownBy(() -> catalog.instantiate(user, "game", Locale.FRANCE))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void list_all_keys_includes_documented_presets() {
        assertThat(catalog.allKeys())
            .contains("game", "description", "store", "release", "igdb");
    }
}
