package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.chat.command.ast.Statement;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class StructuralNodeTypeRegistry {

    private final Map<String, StructuralNodeType> byKeyword = new LinkedHashMap<>();
    private final List<StructuralNodeType> types = new java.util.ArrayList<>();

    public void register(StructuralNodeType type) {
        byKeyword.put(type.keyword(), type);
        types.add(type);
    }

    public Optional<StructuralNodeType> byKeyword(String keyword) {
        return Optional.ofNullable(byKeyword.get(keyword));
    }

    public Optional<StructuralNodeType> forStatement(Statement statement) {
        return types.stream().filter(t -> t.handles(statement)).findFirst();
    }
}
