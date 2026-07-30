package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatCommandLegacyPresetMigrationTest {

    @Test
    void replacesTheEnglishStoreDefaultTemplate() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("store");
        def.setTemplate("Get it here: {game#store#url}");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandLegacyPresetMigration(repository).run();

        assertThat(def.getTemplate()).isEqualTo(
            "{if igdb#getCurrentGame().storeUrl != \"\"}Get it here: {print igdb#getCurrentGame().storeUrl}{/if}");
        assertThat(def.getAst()).isNotNull();
        verify(repository).saveAll(List.of(def));
    }

    @Test
    void replacesTheFrenchReleaseDefaultTemplate() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("release");
        def.setTemplate("Sortie le {game#release_date}");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandLegacyPresetMigration(repository).run();

        assertThat(def.getTemplate()).isEqualTo(
            "{if igdb#getCurrentGame().releaseDate != \"\"}Sortie le {print igdb#getCurrentGame().releaseDate}{/if}");
    }

    @Test
    void replacesTheEnglishIgdbDefaultTemplate() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("igdb");
        def.setTemplate("IGDB page: {game#igdb#url}");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandLegacyPresetMigration(repository).run();

        assertThat(def.getTemplate()).isEqualTo(
            "{if igdb#getCurrentGame().igdbUrl != \"\"}IGDB page: {print igdb#getCurrentGame().igdbUrl}{/if}");
    }

    @Test
    void replacesTheFrenchTriggersDefaultTemplate() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("triggers");
        def.setTemplate(
            "⚠ Triggers connus pour {game#name|ce jeu} : {tw#active|{game#agerating|aucune information disponible}}");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandLegacyPresetMigration(repository).run();

        assertThat(def.getTemplate()).isEqualTo(
            "⚠ Triggers connus pour {if igdb#getCurrentGame().name != \"\"}{print igdb#getCurrentGame().name}{else}ce jeu{/if} : "
                + "{if tw#active() != \"\"}{print tw#active()}{else}{if igdb#getCurrentGame().ageRating != \"\"}"
                + "{print igdb#getCurrentGame().ageRating}{else}aucune information disponible{/if}{/if}");
        assertThat(def.getAst()).isNotNull();
    }

    @Test
    void clearsAnyEjectedJsOnRowsWhoseTemplateGetsReplaced() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("igdb");
        def.setTemplate("IGDB page: {game#igdb#url}");
        def.setEjectedJs("let __output = \"\"; return __output;");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandLegacyPresetMigration(repository).run();

        assertThat(def.getEjectedJs()).isNull();
    }

    @Test
    void leavesACustomizedTemplateUntouched() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setPresetKey("store");
        def.setTemplate("Buy it: {game#store#url}");
        when(repository.findAll()).thenReturn(List.of(def));

        new ChatCommandLegacyPresetMigration(repository).run();

        assertThat(def.getTemplate()).isEqualTo("Buy it: {game#store#url}");
        assertThat(def.getAst()).isNull();
    }

    @Test
    void ignoresACustomCommandWithNoPresetKeyWithoutThrowing() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition custom = new ChatCommandDefinition();
        custom.setPresetKey(null);
        custom.setTemplate("Some fully custom command");
        when(repository.findAll()).thenReturn(List.of(custom));

        new ChatCommandLegacyPresetMigration(repository).run();

        assertThat(custom.getTemplate()).isEqualTo("Some fully custom command");
        verify(repository).saveAll(List.of());
    }

    @Test
    void ignoresRowsWithAnUnrelatedPresetKey() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        ChatCommandDefinition other = new ChatCommandDefinition();
        other.setPresetKey("game");
        other.setTemplate("Currently playing {game#name}");
        when(repository.findAll()).thenReturn(List.of(other));

        new ChatCommandLegacyPresetMigration(repository).run();

        assertThat(other.getTemplate()).isEqualTo("Currently playing {game#name}");
        verify(repository).saveAll(List.of());
    }
}
