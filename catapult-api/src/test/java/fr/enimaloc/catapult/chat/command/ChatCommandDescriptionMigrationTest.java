package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatCommandDescriptionMigrationTest {

    private static final String NEW_ENGLISH_TEMPLATE =
        "{if catapult#getGame().sourceName != \"\"}"
        + "{if catapult#getGame().sourceType == \"STEAM\"}"
        + "{print steam#getGame(catapult#getGame().sourceId, ctx.settings.language).short_description}"
        + "{else}{print igdb#getGame(catapult#getGame().sourceName).summary}{/if}"
        + "{else}I'm not playing anything right now{/if}";

    private static final String NEW_FRENCH_TEMPLATE =
        "{if catapult#getGame().sourceName != \"\"}"
        + "{if catapult#getGame().sourceType == \"STEAM\"}"
        + "{print steam#getGame(catapult#getGame().sourceId, ctx.settings.language).short_description}"
        + "{else}{print igdb#getGame(catapult#getGame().sourceName).summary}{/if}"
        + "{else}Je ne joue à rien actuellement{/if}";

    @Test
    void replacesTheEnglishDefaultTemplate() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("description");
        def.setTemplate("{game#summary|no description available}");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandDescriptionMigration(repository).run();

        assertThat(def.getTemplate()).isEqualTo(NEW_ENGLISH_TEMPLATE);
        assertThat(def.getAst()).isNotNull();
        verify(repository).saveAll(List.of(def));
    }

    @Test
    void replacesTheFrenchDefaultTemplate() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("description");
        def.setTemplate("{game#summary|aucune description disponible}");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandDescriptionMigration(repository).run();

        assertThat(def.getTemplate()).isEqualTo(NEW_FRENCH_TEMPLATE);
    }

    @Test
    void replacesTheIntermediateContextGetMigrationForm() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("description");
        def.setTemplate("{print igdb#getCurrentGame().summary}");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandDescriptionMigration(repository).run();

        assertThat(def.getTemplate()).isEqualTo(NEW_FRENCH_TEMPLATE);
        assertThat(def.getAst()).isNotNull();
    }

    @Test
    void clearsAnyEjectedJsOnRowsWhoseTemplateGetsReplaced() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("description");
        def.setTemplate("{game#summary|no description available}");
        def.setEjectedJs("let __output = \"\"; return __output;");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandDescriptionMigration(repository).run();

        assertThat(def.getEjectedJs()).isNull();
    }

    @Test
    void leavesACustomizedDescriptionTemplateUntouched() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("description");
        def.setTemplate("{game#summary|check the panel below}");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandDescriptionMigration(repository).run();

        assertThat(def.getTemplate()).isEqualTo("{game#summary|check the panel below}");
        assertThat(def.getAst()).isNull();
    }

    @Test
    void ignoresRowsWithAnUnrelatedPresetKey() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition other = new ChatCommandDefinition();
        other.setPresetKey("game");
        other.setTemplate("Currently playing {game#name}");
        when(repository.findAll()).thenReturn(List.of(other));

        new ChatCommandDescriptionMigration(repository).run();

        assertThat(other.getTemplate()).isEqualTo("Currently playing {game#name}");
        verify(repository).saveAll(List.of());
    }
}
