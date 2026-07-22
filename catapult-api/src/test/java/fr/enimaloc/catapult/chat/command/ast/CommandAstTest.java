package fr.enimaloc.catapult.chat.command.ast;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandAstTest {

    private record TestStatement(String label) implements Statement {
        @Override
        public String typeName() {
            return "test-statement";
        }
    }

    @Test
    void astExposesItsStatementList() {
        CommandAst ast = new CommandAst(List.of(new TestStatement("a"), new TestStatement("b")));

        assertThat(ast.statements()).hasSize(2);
        assertThat(ast.statements().get(0).typeName()).isEqualTo("test-statement");
    }

    @Test
    void valueTypeHasFourVariants() {
        assertThat(ValueType.values()).containsExactly(
            ValueType.STRING, ValueType.NUMBER, ValueType.BOOLEAN, ValueType.LIST);
    }
}
