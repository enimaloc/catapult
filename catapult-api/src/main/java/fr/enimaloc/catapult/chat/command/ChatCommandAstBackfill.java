package fr.enimaloc.catapult.chat.command;

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

    @Override
    public void run(String... args) {
        List<ChatCommandDefinition> pending = repository.findAll().stream()
            .filter(def -> def.getAst() == null)
            .toList();
        List<ChatCommandDefinition> converted = new ArrayList<>();
        for (ChatCommandDefinition def : pending) {
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
}
