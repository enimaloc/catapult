package fr.enimaloc.catapult.chat.command.ast;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExpressionNodesTest {

    @Test
    void literalExprCarriesValueAndType() {
        LiteralExpr expr = new LiteralExpr("Valorant", ValueType.STRING);
        assertThat(expr.typeName()).isEqualTo("literal");
        assertThat(expr.value()).isEqualTo("Valorant");
        assertThat(expr.type()).isEqualTo(ValueType.STRING);
    }

    @Test
    void varRefExprCarriesName() {
        VarRefExpr expr = new VarRefExpr("msg");
        assertThat(expr.typeName()).isEqualTo("var-ref");
        assertThat(expr.name()).isEqualTo("msg");
    }

    @Test
    void contextGetExprCarriesPath() {
        ContextGetExpr expr = new ContextGetExpr("game#name");
        assertThat(expr.typeName()).isEqualTo("context-get");
        assertThat(expr.path()).isEqualTo("game#name");
    }

    @Test
    void serviceCallExprCarriesNamespaceFunctionAndArgs() {
        ServiceCallExpr expr = new ServiceCallExpr("igdb", "getGame",
            List.of(new ContextGetExpr("game#name")));
        assertThat(expr.typeName()).isEqualTo("service-call");
        assertThat(expr.namespace()).isEqualTo("igdb");
        assertThat(expr.function()).isEqualTo("getGame");
        assertThat(expr.args()).containsExactly(new ContextGetExpr("game#name"));
    }

    @Test
    void binaryExprCarriesOperandsAndOperator() {
        BinaryExpr expr = new BinaryExpr(
            new ContextGetExpr("game#name"), "==", new LiteralExpr("Valorant", ValueType.STRING));
        assertThat(expr.typeName()).isEqualTo("binary");
        assertThat(expr.operator()).isEqualTo("==");
    }
}
