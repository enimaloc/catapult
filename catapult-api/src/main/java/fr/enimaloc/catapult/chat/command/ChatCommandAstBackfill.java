package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

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
        for (ChatCommandDefinition def : pending) {
            def.setAst(converter.toAstJson(def.getTemplate()));
        }
        repository.saveAll(pending);
    }
}
