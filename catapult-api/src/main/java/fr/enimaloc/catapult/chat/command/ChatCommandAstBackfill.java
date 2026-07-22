package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatCommandAstBackfill implements CommandLineRunner {

    private final ChatCommandDefinitionRepository repository;
    private final LegacyTemplateConverter converter;
    private final NodeJsonCodec codec = new NodeJsonCodec();

    @Override
    public void run(String... args) {
        List<ChatCommandDefinition> converted = new ArrayList<>();
        for (ChatCommandDefinition def : repository.findAll()) {
            if (!needsBackfill(def)) {
                continue;
            }
            try {
                def.setAst(converter.toAstJson(def.getTemplate()));
                converted.add(def);
            } catch (RuntimeException e) {
                log.warn("Skipping AST backfill for chat command id={} name={}: {}",
                    def.getId(), def.getName(), e.getMessage());
            }
        }
        repository.saveAll(converted);
    }

    /**
     * A row needs (re-)backfilling if it has no {@code ast} yet, or if its {@code ast} is still
     * in the pre-rewrite expression-tree shape (a stale row from a previous run of this backfill,
     * before the AST was rewritten to the statement-based model). If the shape can't be
     * determined (malformed JSON), err on the side of re-deriving it from the template.
     */
    private boolean needsBackfill(ChatCommandDefinition def) {
        String ast = def.getAst();
        if (ast == null) {
            return true;
        }
        try {
            return codec.isOldShape(ast);
        } catch (RuntimeException e) {
            return true;
        }
    }
}
