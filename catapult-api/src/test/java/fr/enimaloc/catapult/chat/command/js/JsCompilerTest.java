package fr.enimaloc.catapult.chat.command.js;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.ForEachNode;
import fr.enimaloc.catapult.chat.command.ast.PlaceholderNode;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsCompilerTest {

    private final CommandDslParser parser = new CommandDslParser();
    private final JsCompiler compiler = new JsCompiler();

    @Test
    void compilesLiteralAndPlaceholder() {
        String js = compiler.compile(parser.parse("Now playing {game#name}!"));
        assertThat(js).contains("result += \"Now playing \";");
        assertThat(js).contains("result += ctx.placeholder(\"game#name\");");
        assertThat(js).contains("result += \"!\";");
        assertThat(js).contains("return result;");
    }

    @Test
    void compilesServiceCall() {
        String js = compiler.compile(parser.parse("{igdb#getGame(\"Valorant\")}"));
        assertThat(js).contains("result += ctx.call(\"igdb\", \"getGame\", \"Valorant\");");
    }

    @Test
    void compilesIfElse() {
        String js = compiler.compile(parser.parse("{if game#name == \"Valorant\"}yes{else}no{/if}"));
        assertThat(js).contains("if (ctx.placeholder(\"game#name\") === \"Valorant\") {");
        assertThat(js).contains("result += \"yes\";");
        assertThat(js).contains("} else {");
        assertThat(js).contains("result += \"no\";");
    }

    @Test
    void compilesForEach() {
        String js = compiler.compile(parser.parse("{for f in fallbacks}-{f} {/for}"));
        assertThat(js).contains("for (const f of ctx.list(\"fallbacks\")) {");
        assertThat(js).contains("result += \"-\";");
        assertThat(js).contains("result += f;");
    }

    @Test
    void escapesQuotesAndBackslashesInPlaceholderPath() {
        CommandAst ast = new CommandAst(List.of(new PlaceholderNode("weird\"path\\here")));
        String js = compiler.compile(ast);
        assertThat(js).contains("ctx.placeholder(\"weird\\\"path\\\\here\")");
    }

    @Test
    void escapesQuotesAndBackslashesInForEachListSource() {
        CommandAst ast = new CommandAst(List.of(new ForEachNode("f", "weird\"list\\name", List.of())));
        String js = compiler.compile(ast);
        assertThat(js).contains("ctx.list(\"weird\\\"list\\\\name\")");
    }
}
