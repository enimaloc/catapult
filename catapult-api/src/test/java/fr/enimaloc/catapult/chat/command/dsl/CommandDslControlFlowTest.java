
package fr.enimaloc.catapult.chat.command.dsl;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.ForEachNode;
import fr.enimaloc.catapult.chat.command.ast.IfNode;
import fr.enimaloc.catapult.chat.command.ast.LiteralNode;
import fr.enimaloc.catapult.chat.command.ast.PlaceholderNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandDslControlFlowTest {

    private final CommandDslParser parser = new CommandDslParser();
    private final CommandDslGenerator generator = new CommandDslGenerator();

    @Test
    void parsesIfElse() {
        CommandAst ast = parser.parse("{if game#name == \"Valorant\"}yes{else}no{/if}");
        IfNode ifNode = (IfNode) ast.nodes().get(0);
        assertThat(ifNode.left()).isEqualTo(new PlaceholderNode("game#name"));
        assertThat(ifNode.operator()).isEqualTo("==");
        assertThat(ifNode.right()).isEqualTo(new LiteralNode("Valorant"));
        assertThat(ifNode.thenBranch()).containsExactly(new LiteralNode("yes"));
        assertThat(ifNode.elseBranch()).containsExactly(new LiteralNode("no"));
    }

    @Test
    void ifElseRoundTrips() {
        String source = "{if game#name == \"Valorant\"}yes{else}no{/if}";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void parsesForEachOverFallbacks() {
        CommandAst ast = parser.parse("{for f in fallbacks}-{f} {/for}");
        ForEachNode forNode = (ForEachNode) ast.nodes().get(0);
        assertThat(forNode.bindingName()).isEqualTo("f");
        assertThat(forNode.listSource()).isEqualTo("fallbacks");
        assertThat(forNode.body()).containsExactly(
            new LiteralNode("-"),
            new PlaceholderNode("f"),
            new LiteralNode(" ")
        );
    }

    @Test
    void forEachRoundTrips() {
        String source = "{for f in fallbacks}-{f} {/for}";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void ifWithoutElseRoundTrips() {
        String source = "{if a == b}yes{/if}";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void malformedIfConditionThrowsParseException() {
        assertThatThrownBy(() -> parser.parse("{if broken}x{/if}"))
            .isInstanceOf(CommandDslParseException.class);
    }

    @Test
    void malformedForHeaderThrowsParseException() {
        assertThatThrownBy(() -> parser.parse("{for broken}x{/for}"))
            .isInstanceOf(CommandDslParseException.class);
    }

    @Test
    void unclosedIfThrowsParseException() {
        assertThatThrownBy(() -> parser.parse("{if a == b}yes"))
            .isInstanceOf(CommandDslParseException.class);
    }

    @Test
    void unclosedForThrowsParseException() {
        assertThatThrownBy(() -> parser.parse("{for f in fallbacks}x"))
            .isInstanceOf(CommandDslParseException.class);
    }
}
