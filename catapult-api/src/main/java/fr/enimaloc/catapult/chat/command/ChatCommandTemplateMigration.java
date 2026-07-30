package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslGenerator;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Re-derives the stored {@code template} text from {@code ast} for every row, one time per boot,
 * so older commands pick up the current generator's canonical spelling (e.g. the "ctx.game.name"
 * dot-chain sugar replacing bare "game#name", and "." replacing "get(obj, \"prop\")" for property
 * access) without any change to the underlying ast/behavior. Runs after {@link ChatCommandAstBackfill}
 * so every row has an ast to regenerate from.
 */
@Slf4j
@Component
@Order(2)
public class ChatCommandTemplateMigration implements CommandLineRunner {

    private final ChatCommandDefinitionRepository repository;
    private final CommandDslGenerator generator = new CommandDslGenerator();
    private final NodeJsonCodec codec = new NodeJsonCodec();

    public ChatCommandTemplateMigration(ChatCommandDefinitionRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        List<ChatCommandDefinition> changed = new ArrayList<>();
        for (ChatCommandDefinition def : repository.findAll()) {
            String regenerated = regenerate(def);
            if (regenerated != null && !regenerated.equals(def.getTemplate())) {
                def.setTemplate(regenerated);
                changed.add(def);
            }
        }
        repository.saveAll(changed);
    }

    private String regenerate(ChatCommandDefinition def) {
        String ast = def.getAst();
        if (ast == null) {
            return null;
        }
        try {
            CommandAst decoded = codec.fromJson(ast);
            return generator.generate(decoded);
        } catch (RuntimeException e) {
            log.warn("Skipping template migration for chat command id={} name={}: {}",
                def.getId(), def.getName(), e.getMessage());
            return null;
        }
    }
}
