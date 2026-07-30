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
 * One-time migration for the {@code game} preset's new default template — same treatment as
 * {@link ChatCommandDescriptionMigration}, applied to the summary half of {@code !game}: it used
 * to read the legacy {@code {game#summary}} placeholder, and now branches Steam-vs-IGDB and reads
 * {@code ctx.settings.language} so the description text matches the streamer's own language. Only
 * rows whose stored template still exactly matches one of the old hardcoded defaults are
 * rewritten — a genuine customization is left untouched.
 */
@Slf4j
@Component
@Order(6)
public class ChatCommandGameMigration implements CommandLineRunner {

    private static final String PRESET_KEY = "game";

    private static final String NEW_DEFAULT_TEMPLATE_ENGLISH =
        "{if catapult#getGame().sourceName != \"\"}Currently playing "
        + "{print catapult#getGame().sourceName} — "
        + "{if catapult#getGame().sourceType == \"STEAM\"}"
        + "{print steam#getGame(catapult#getGame().sourceId, ctx.settings.language).short_description}"
        + "{else}{print igdb#getGame(catapult#getGame().sourceName).summary}{/if}{/if}";

    private static final String NEW_DEFAULT_TEMPLATE_FRENCH =
        "{if catapult#getGame().sourceName != \"\"}Je joue à "
        + "{print catapult#getGame().sourceName} — "
        + "{if catapult#getGame().sourceType == \"STEAM\"}"
        + "{print steam#getGame(catapult#getGame().sourceId, ctx.settings.language).short_description}"
        + "{else}{print igdb#getGame(catapult#getGame().sourceName).summary}{/if}{/if}";

    // The exact two hardcoded defaults the "game" preset ever shipped with (English/French
    // messages.properties — see git history), mapped to their language-matched replacement.
    private static final Map<String, String> OLD_TO_NEW_TEMPLATE = Map.of(
        "Currently playing {game#name} — {game#summary|}", NEW_DEFAULT_TEMPLATE_ENGLISH,
        "Je joue à {game#name} — {game#summary|}", NEW_DEFAULT_TEMPLATE_FRENCH
    );

    private final ChatCommandDefinitionRepository repository;
    private final CommandDslParser parser = new CommandDslParser();
    private final NodeJsonCodec codec = new NodeJsonCodec();

    public ChatCommandGameMigration(ChatCommandDefinitionRepository repository) {
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
            log.info("Migrated {} 'game' preset row(s) to the Steam/IGDB default template", changed.size());
        }
        repository.saveAll(changed);
    }
}
