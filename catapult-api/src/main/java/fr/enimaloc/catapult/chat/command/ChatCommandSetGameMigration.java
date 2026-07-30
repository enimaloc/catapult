package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.chat.ChatCommandPresetCatalog;
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
import java.util.Set;

/**
 * One-time migration for the removal of {@code SetGameCommand} (the last Java-hardcoded {@code
 * ChatCommand} bean with an editable template — {@code !debug} is the only static bean left now,
 * and it's owner-only, never gets a UI row at all): {@code !setgame} is now a fully data-driven
 * {@link ChatCommandPresetCatalog} preset ({@code chat.preset.setgame.*}) exactly like {@code
 * !game}/{@code !description}/etc., not a special case {@link CommandRegistry}/{@link
 * fr.enimaloc.catapult.chat.DynamicCommandResolver} need to know about.
 *
 * <p>Every existing row seeded by the old {@code ChatCommandPresetCatalog#ensureBuiltins} still
 * has {@code presetKey = "builtin:setgame"} — {@link fr.enimaloc.catapult.chat.DynamicCommandResolver}
 * already degrades that gracefully to a normal {@link fr.enimaloc.catapult.chat.DynamicChatCommand}
 * once no static bean answers to it (so nothing would actually break left as-is), but the
 * presetKey staying "builtin:*" post-migration is misleading (it no longer refers to a Java bean)
 * and blocks {@link ChatCommandPresetCatalog#instantiate}'s own idempotency check from ever
 * recognizing these rows as "the setgame preset already exists" the way it does for every other
 * preset. This renames the presetKey to {@code "setgame"} for all of them, and additionally
 * replaces the stored template with the new default — but ONLY for rows whose template still
 * exactly matches one of the old hardcoded defaults, i.e. the streamer never customized it;
 * anything else is a genuine customization and is left untouched.
 */
@Slf4j
@Component
@Order(4)
public class ChatCommandSetGameMigration implements CommandLineRunner {

    private static final String OLD_PRESET_KEY = ChatCommandPresetCatalog.BUILTIN_PRESET_KEY_PREFIX + "setgame";
    private static final String NEW_PRESET_KEY = "setgame";

    // The exact two hardcoded defaults SetGameCommand ever shipped with (English/French
    // messages.properties — see git history), before this migration existed.
    private static final Set<String> OLD_DEFAULT_TEMPLATES = Set.of(
        "Game updated: {args}",
        "Jeu mis à jour : {args}"
    );

    private static final String NEW_DEFAULT_TEMPLATE =
        "{var game = arr#join(list(args), \" \")}"
        + "{if igdb#getGame(game).id != \"\"}"
        + "{catapult#setGame(game, igdb#getGame(game).id)}Jeu mis à jour: {game}"
        + "{else}{game} non trouvé{/if}";

    private final ChatCommandDefinitionRepository repository;
    private final CommandDslParser parser = new CommandDslParser();
    private final NodeJsonCodec codec = new NodeJsonCodec();

    public ChatCommandSetGameMigration(ChatCommandDefinitionRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        String newDefaultAst = codec.toJson(parser.parse(NEW_DEFAULT_TEMPLATE));
        List<ChatCommandDefinition> changed = new ArrayList<>();
        for (ChatCommandDefinition def : repository.findAll()) {
            if (!OLD_PRESET_KEY.equals(def.getPresetKey())) {
                continue;
            }
            def.setPresetKey(NEW_PRESET_KEY);
            if (OLD_DEFAULT_TEMPLATES.contains(def.getTemplate())) {
                def.setTemplate(NEW_DEFAULT_TEMPLATE);
                def.setAst(newDefaultAst);
                def.setEjectedJs(null);
            }
            changed.add(def);
        }
        if (!changed.isEmpty()) {
            log.info("Migrated {} 'builtin:setgame' row(s) to the data-driven 'setgame' preset", changed.size());
        }
        repository.saveAll(changed);
    }
}
