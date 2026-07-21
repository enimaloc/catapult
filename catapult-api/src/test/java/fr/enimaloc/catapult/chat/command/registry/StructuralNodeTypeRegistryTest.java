package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.chat.command.ast.CommandNode;
import fr.enimaloc.catapult.chat.command.ast.ForEachNode;
import fr.enimaloc.catapult.chat.command.ast.IfNode;
import fr.enimaloc.catapult.chat.command.ast.LiteralNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StructuralNodeTypeRegistryTest {

    @Test
    void registeredTypesAreFoundByKeywordAndByNode() {
        StructuralNodeTypeRegistry registry = new StructuralNodeTypeRegistry();
        registry.register(new IfNodeType());
        registry.register(new ForEachNodeType());

        assertThat(registry.byKeyword("if")).isPresent();
        assertThat(registry.byKeyword("for")).isPresent();
        assertThat(registry.byKeyword("switch")).isEmpty();

        CommandNode ifNode = new IfNode(new LiteralNode("a"), "==", new LiteralNode("a"),
            List.of(), List.of());
        assertThat(registry.forNode(ifNode)).map(StructuralNodeType::keyword).contains("if");

        CommandNode forNode = new ForEachNode("f", "fallbacks", List.of());
        assertThat(registry.forNode(forNode)).map(StructuralNodeType::keyword).contains("for");
    }

    @Test
    void unregisteredNewNodeTypeIsSimplyAbsent() {
        StructuralNodeTypeRegistry registry = new StructuralNodeTypeRegistry();
        Optional<StructuralNodeType> found = registry.forNode(new LiteralNode("plain text"));
        assertThat(found).isEmpty();
    }
}
