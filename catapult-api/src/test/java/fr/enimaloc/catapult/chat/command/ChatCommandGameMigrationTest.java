package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatCommandGameMigrationTest {

    private static final String NEW_ENGLISH_TEMPLATE =
        "{if catapult#getGame().sourceName != \"\"}Currently playing "
        + "{print catapult#getGame().sourceName} — "
        + "{if catapult#getGame().sourceType == \"STEAM\"}"
        + "{print steam#getGame(catapult#getGame().sourceId, ctx.settings.language).short_description}"
        + "{else}{print igdb#getGame(catapult#getGame().sourceName).summary}{/if}{/if}";

    private static final String NEW_FRENCH_TEMPLATE =
        "{if catapult#getGame().sourceName != \"\"}Je joue à "
        + "{print catapult#getGame().sourceName} — "
        + "{if catapult#getGame().sourceType == \"STEAM\"}"
        + "{print steam#getGame(catapult#getGame().sourceId, ctx.settings.language).short_description}"
        + "{else}{print igdb#getGame(catapult#getGame().sourceName).summary}{/if}{/if}";

    @Test
    void replacesTheEnglishDefaultTemplate() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("game");
        def.setTemplate("Currently playing {game#name} — {game#summary|}");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandGameMigration(repository).run();

        assertThat(def.getTemplate()).isEqualTo(NEW_ENGLISH_TEMPLATE);
        assertThat(def.getAst()).isNotNull();
        verify(repository).saveAll(List.of(def));
    }

    @Test
    void replacesTheFrenchDefaultTemplate() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("game");
        def.setTemplate("Je joue à {game#name} — {game#summary|}");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandGameMigration(repository).run();

        assertThat(def.getTemplate()).isEqualTo(NEW_FRENCH_TEMPLATE);
    }

    @Test
    void clearsAnyEjectedJsOnRowsWhoseTemplateGetsReplaced() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("game");
        def.setTemplate("Currently playing {game#name} — {game#summary|}");
        def.setEjectedJs("let __output = \"\"; return __output;");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandGameMigration(repository).run();

        assertThat(def.getEjectedJs()).isNull();
    }

    @Test
    void leavesACustomizedGameTemplateUntouched() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("game");
        def.setTemplate("On joue à {game#name} !");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandGameMigration(repository).run();

        assertThat(def.getTemplate()).isEqualTo("On joue à {game#name} !");
        assertThat(def.getAst()).isNull();
    }

    @Test
    void ignoresRowsWithAnUnrelatedPresetKey() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition other = new ChatCommandDefinition();
        other.setPresetKey("store");
        other.setTemplate("Get it here: {game#store#url}");
        when(repository.findAll()).thenReturn(List.of(other));

        new ChatCommandGameMigration(repository).run();

        assertThat(other.getTemplate()).isEqualTo("Get it here: {game#store#url}");
        verify(repository).saveAll(List.of());
    }
}
