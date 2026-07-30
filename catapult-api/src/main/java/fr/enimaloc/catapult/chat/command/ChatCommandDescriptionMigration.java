package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One-time migration for the {@code description} preset's new default template: it used to be a
 * single {@code {game#summary|...}} placeholder, and is now a Steam-vs-IGDB branch that reads
 * {@code ctx.settings.language} so the returned description text matches the streamer's own
 * language. Only rows whose stored template still exactly matches one of the old hardcoded
 * defaults (English/French) are rewritten — a genuine customization is left untouched, same as
 * {@link ChatCommandSetGameMigration}.
 */
@Slf4j
@Component
@Order(5)
public class ChatCommandDescriptionMigration implements CommandLineRunner {

    private static final String PRESET_KEY = "description";

    private static final String NEW_DEFAULT_TEMPLATE_ENGLISH =
        "{if catapult#getGame().sourceName != \"\"}"
        + "{if catapult#getGame().sourceType == \"STEAM\"}"
        + "{print steam#getGame(catapult#getGame().sourceId, ctx.settings.language).short_description}"
        + "{else}{print igdb#getGame(catapult#getGame().sourceName).summary}{/if}"
        + "{else}I'm not playing anything right now{/if}";

    private static final String NEW_DEFAULT_TEMPLATE_FRENCH =
        "{if catapult#getGame().sourceName != \"\"}"
        + "{if catapult#getGame().sourceType == \"STEAM\"}"
        + "{print steam#getGame(catapult#getGame().sourceId, ctx.settings.language).short_description}"
        + "{else}{print igdb#getGame(catapult#getGame().sourceName).summary}{/if}"
        + "{else}Je ne joue à rien actuellement{/if}";

    // The exact two hardcoded defaults the "description" preset ever shipped with (English/French
    // messages.properties — see git history), mapped to their language-matched replacement.
    private static final Map<String, String> OLD_TO_NEW_TEMPLATE = Map.of(
        "{game#summary|no description available}", NEW_DEFAULT_TEMPLATE_ENGLISH,
        "{game#summary|aucune description disponible}", NEW_DEFAULT_TEMPLATE_FRENCH
    );

    private final ChatCommandDefinitionRepository repository;
    private final CommandDslParser parser = new CommandDslParser();
    private final NodeJsonCodec codec = new NodeJsonCodec();

    public ChatCommandDescriptionMigration(ChatCommandDefinitionRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        List<ChatCommandDefinition> changed = new ArrayList<>();
        for (ChatCommandDefinition def : repository.findAll()) {
            if (!PRESET_KEY.equals(def.getPresetKey())) {
                continue;
            }
            String newTemplate = OLD_TO_NEW_TEMPLATE.get(def.getTemplate());
            if (newTemplate == null) {
                continue;
            }
            def.setTemplate(newTemplate);
            def.setAst(codec.toJson(parser.parse(newTemplate)));
            def.setEjectedJs(null);
            changed.add(def);
        }
        if (!changed.isEmpty()) {
            log.info("Migrated {} 'description' preset row(s) to the Steam/IGDB default template", changed.size());
        }
        repository.saveAll(changed);
    }
}
