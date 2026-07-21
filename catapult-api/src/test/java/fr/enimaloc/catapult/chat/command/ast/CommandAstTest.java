package fr.enimaloc.catapult.chat.command.ast;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CommandAstTest {

    @Test
    void astExposesItsFlatNodeList() {
        CommandAst ast = new CommandAst(List.of(
            new LiteralNode("Hello "),
            new PlaceholderNode("game#name")
        ));

        assertThat(ast.nodes()).hasSize(2);
        assertThat(ast.nodes().get(0).typeName()).isEqualTo("literal");
        assertThat(ast.nodes().get(1).typeName()).isEqualTo("placeholder");
    }

    @Test
    void serviceCallNodeCarriesNamespaceFunctionAndArgs() {
        ServiceCallNode call = new ServiceCallNode("igdb", "getGame",
            List.of(new PlaceholderNode("game#name")));

        assertThat(call.typeName()).isEqualTo("service-call");
        assertThat(call.namespace()).isEqualTo("igdb");
        assertThat(call.function()).isEqualTo("getGame");
        assertThat(call.args()).hasSize(1);
    }
}
