package fr.enimaloc.catapult.chat.command.dsl;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.LiteralNode;
import fr.enimaloc.catapult.chat.command.ast.PlaceholderNode;
import fr.enimaloc.catapult.chat.command.ast.ServiceCallNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandDslParserTest {

    private final CommandDslParser parser = new CommandDslParser();

    @Test
    void parsesLiteralTextOnly() {
        CommandAst ast = parser.parse("Hello world");
        assertThat(ast.nodes()).containsExactly(new LiteralNode("Hello world"));
    }

    @Test
    void parsesLiteralAndPlaceholder() {
        CommandAst ast = parser.parse("Now playing {game#name}!");
        assertThat(ast.nodes()).containsExactly(
            new LiteralNode("Now playing "),
            new PlaceholderNode("game#name"),
            new LiteralNode("!")
        );
    }

    @Test
    void parsesServiceCallWithLiteralArgument() {
        CommandAst ast = parser.parse("{igdb#getGame(\"Valorant\")}");
        assertThat(ast.nodes()).hasSize(1);
        ServiceCallNode call = (ServiceCallNode) ast.nodes().get(0);
        assertThat(call.namespace()).isEqualTo("igdb");
        assertThat(call.function()).isEqualTo("getGame");
        assertThat(call.args()).containsExactly(new LiteralNode("Valorant"));
    }

    @Test
    void parsesServiceCallWithPlaceholderArgument() {
        CommandAst ast = parser.parse("{steam#getPrice(game#store#steam)}");
        ServiceCallNode call = (ServiceCallNode) ast.nodes().get(0);
        assertThat(call.args()).containsExactly(new PlaceholderNode("game#store#steam"));
    }

    @Test
    void throwsOnUnclosedBrace() {
        assertThatThrownBy(() -> parser.parse("Hello {world"))
            .isInstanceOf(CommandDslParseException.class);
    }

    @Test
    void parsesServiceCallWithLiteralArgumentContainingComma() {
        CommandAst ast = parser.parse("{igdb#getGame(\"Half-Life, part 2\")}");
        assertThat(ast.nodes()).hasSize(1);
        ServiceCallNode call = (ServiceCallNode) ast.nodes().get(0);
        assertThat(call.args()).containsExactly(new LiteralNode("Half-Life, part 2"));
    }
}
