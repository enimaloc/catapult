package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatCommandSetGameMigrationTest {

    private static final String NEW_DEFAULT_TEMPLATE =
        "{var game = arr#join(list(args), \" \")}"
        + "{if igdb#getGame(game).id != \"\"}"
        + "{catapult#setGame(game, igdb#getGame(game).id)}Jeu mis à jour: {game}"
        + "{else}{game} non trouvé{/if}";

    @Test
    void renamesThePresetKeyAndReplacesTheTemplateWhenItStillMatchesTheOldFrenchDefault() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("builtin:setgame");
        def.setTemplate("Jeu mis à jour : {args}");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandSetGameMigration(repository).run();

        assertThat(def.getPresetKey()).isEqualTo("setgame");
        assertThat(def.getTemplate()).isEqualTo(NEW_DEFAULT_TEMPLATE);
        assertThat(def.getAst()).isNotNull();
        verify(repository).saveAll(List.of(def));
    }

    @Test
    void renamesThePresetKeyAndReplacesTheTemplateWhenItStillMatchesTheOldEnglishDefault() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("builtin:setgame");
        def.setTemplate("Game updated: {args}");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandSetGameMigration(repository).run();

        assertThat(def.getPresetKey()).isEqualTo("setgame");
        assertThat(def.getTemplate()).isEqualTo(NEW_DEFAULT_TEMPLATE);
    }

    @Test
    void renamesThePresetKeyButLeavesACustomizedTemplateUntouched() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("builtin:setgame");
        def.setTemplate("Now playing whatever you say: {args}");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandSetGameMigration(repository).run();

        assertThat(def.getPresetKey()).isEqualTo("setgame");
        assertThat(def.getTemplate()).isEqualTo("Now playing whatever you say: {args}");
        assertThat(def.getAst()).isNull();
    }

    @Test
    void clearsAnyEjectedJsOnRowsWhoseTemplateGetsReplaced() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("builtin:setgame");
        def.setTemplate("Jeu mis à jour : {args}");
        def.setEjectedJs("let __output = \"\"; return __output;");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandSetGameMigration(repository).run();

        assertThat(def.getEjectedJs()).isNull();
    }

    @Test
    void ignoresRowsWithAnUnrelatedPresetKey() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition other = new ChatCommandDefinition();
        other.setPresetKey("game");
        other.setTemplate("Currently playing {game#name}");
        ChatCommandDefinition custom = new ChatCommandDefinition();
        custom.setPresetKey(null);
        custom.setTemplate("Some custom command");
        when(repository.findAll()).thenReturn(List.of(other, custom));

        new ChatCommandSetGameMigration(repository).run();

        assertThat(other.getTemplate()).isEqualTo("Currently playing {game#name}");
        assertThat(custom.getTemplate()).isEqualTo("Some custom command");
        verify(repository).saveAll(List.of());
    }

    @Test
    void doesNotSaveAtAllWhenThereIsNothingToMigrate() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        when(repository.findAll()).thenReturn(List.of());

        new ChatCommandSetGameMigration(repository).run();

        verify(repository).saveAll(argThat(saved -> !saved.iterator().hasNext()));
    }
}
