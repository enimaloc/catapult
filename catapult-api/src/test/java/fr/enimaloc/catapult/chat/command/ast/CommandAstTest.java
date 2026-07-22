package fr.enimaloc.catapult.chat.command.ast;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandAstTest {

    @Test
    void astExposesItsStatementList() {
        CommandAst ast = new CommandAst(List.of(
            new PrintStatement(new LiteralExpr("Hello ", ValueType.STRING)),
            new PrintStatement(new ContextGetExpr("game#name"))
        ));

        assertThat(ast.statements()).hasSize(2);
        assertThat(ast.statements().get(0).typeName()).isEqualTo("print");
    }

    @Test
    void valueTypeHasFourVariants() {
        assertThat(ValueType.values()).containsExactly(
            ValueType.STRING, ValueType.NUMBER, ValueType.BOOLEAN, ValueType.LIST);
    }
}
