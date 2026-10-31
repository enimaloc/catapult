package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.chat.command.ast.BinaryExpr;
import fr.enimaloc.catapult.chat.command.ast.ForEachStatement;
import fr.enimaloc.catapult.chat.command.ast.IfStatement;
import fr.enimaloc.catapult.chat.command.ast.LiteralExpr;
import fr.enimaloc.catapult.chat.command.ast.PrintStatement;
import fr.enimaloc.catapult.chat.command.ast.Statement;
import fr.enimaloc.catapult.chat.command.ast.ValueType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StructuralNodeTypeRegistryTest {

    @Test
    void registeredTypesAreFoundByKeywordAndByStatement() {
        StructuralNodeTypeRegistry registry = new StructuralNodeTypeRegistry();
        registry.register(new IfNodeType());
        registry.register(new ForEachNodeType());

        assertThat(registry.byKeyword("if")).isPresent();
        assertThat(registry.byKeyword("for")).isPresent();
        assertThat(registry.byKeyword("switch")).isEmpty();

        Statement ifStatement = new IfStatement(
            new BinaryExpr(new LiteralExpr("a", ValueType.STRING), "==", new LiteralExpr("a", ValueType.STRING)),
            List.of(), List.of());
        assertThat(registry.forStatement(ifStatement)).map(StructuralNodeType::keyword).contains("if");

        Statement forStatement = new ForEachStatement("f", "fallbacks", List.of());
        assertThat(registry.forStatement(forStatement)).map(StructuralNodeType::keyword).contains("for");
    }

    @Test
    void unregisteredNewStatementTypeIsSimplyAbsent() {
        StructuralNodeTypeRegistry registry = new StructuralNodeTypeRegistry();
        Optional<StructuralNodeType> found = registry.forStatement(new PrintStatement(new LiteralExpr("x", ValueType.STRING)));
        assertThat(found).isEmpty();
    }
}
