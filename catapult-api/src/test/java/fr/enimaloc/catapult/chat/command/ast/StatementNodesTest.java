package fr.enimaloc.catapult.chat.command.ast;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatementNodesTest {

    @Test
    void varDeclStatementCarriesNameTypeAndInit() {
        VarDeclStatement s = new VarDeclStatement("msg", ValueType.STRING, new LiteralExpr("", ValueType.STRING));
        assertThat(s.typeName()).isEqualTo("var-decl");
        assertThat(s.name()).isEqualTo("msg");
        assertThat(s.type()).isEqualTo(ValueType.STRING);
    }

    @Test
    void assignStatementCarriesNameAndExpr() {
        AssignStatement s = new AssignStatement("msg", new LiteralExpr("hi", ValueType.STRING));
        assertThat(s.typeName()).isEqualTo("assign");
        assertThat(s.name()).isEqualTo("msg");
    }

    @Test
    void concatStatementCarriesNameAndExpr() {
        ConcatStatement s = new ConcatStatement("msg", new ContextGetExpr("game#name"));
        assertThat(s.typeName()).isEqualTo("concat");
        assertThat(s.expr()).isEqualTo(new ContextGetExpr("game#name"));
    }

    @Test
    void printStatementCarriesExpr() {
        PrintStatement s = new PrintStatement(new VarRefExpr("msg"));
        assertThat(s.typeName()).isEqualTo("print");
    }

    @Test
    void ifStatementCarriesConditionAndBranches() {
        BinaryExpr condition = new BinaryExpr(
            new ContextGetExpr("game#name"), "==", new LiteralExpr("Valorant", ValueType.STRING));
        IfStatement s = new IfStatement(condition,
            List.of(new PrintStatement(new LiteralExpr("ranked", ValueType.STRING))),
            List.of());
        assertThat(s.typeName()).isEqualTo("if");
        assertThat(s.thenBranch()).hasSize(1);
        assertThat(s.elseBranch()).isEmpty();
    }

    @Test
    void forEachStatementCarriesBindingListSourceAndBody() {
        ForEachStatement s = new ForEachStatement("f", "fallbacks",
            List.of(new PrintStatement(new VarRefExpr("f"))));
        assertThat(s.typeName()).isEqualTo("for-each");
        assertThat(s.bindingName()).isEqualTo("f");
        assertThat(s.listSource()).isEqualTo("fallbacks");
    }
}
