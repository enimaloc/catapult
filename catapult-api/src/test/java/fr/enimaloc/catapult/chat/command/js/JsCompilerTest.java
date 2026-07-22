package fr.enimaloc.catapult.chat.command.js;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.ForEachStatement;
import fr.enimaloc.catapult.chat.command.ast.PrintStatement;
import fr.enimaloc.catapult.chat.command.ast.VarRefExpr;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsCompilerTest {

    private final CommandDslParser parser = new CommandDslParser();
    private final JsCompiler compiler = new JsCompiler();

    @Test
    void compilesLiteralAndBareContextGet() {
        String js = compiler.compile(parser.parse("Now playing {game#name}!"));
        assertThat(js).contains("let __output = \"\";");
        assertThat(js).contains("__output += (\"Now playing \");");
        assertThat(js).contains("__output += (ctx.placeholder(\"game#name\"));");
        assertThat(js).contains("__output += (\"!\");");
        assertThat(js).contains("return __output;");
    }

    @Test
    void compilesVarDeclAssignAndConcat() {
        String js = compiler.compile(parser.parse(
            "{var msg = \"\"}{msg = msg + \"Now playing \"}{msg = msg + get(game#name)}{print msg}"));
        assertThat(js).contains("let msg = \"\";");
        assertThat(js).contains("msg = msg + (\"Now playing \");");
        assertThat(js).contains("msg = msg + (ctx.placeholder(\"game#name\"));");
        assertThat(js).contains("__output += (msg);");
    }

    @Test
    void compilesServiceCall() {
        String js = compiler.compile(parser.parse("{igdb#getGame(\"Valorant\")}"));
        assertThat(js).contains("__output += (ctx.call(\"igdb\", \"getGame\", \"Valorant\"));");
    }

    @Test
    void compilesIfElseWithStrictEquality() {
        String js = compiler.compile(parser.parse("{if game#name == \"Valorant\"}yes{else}no{/if}"));
        assertThat(js).contains("if (ctx.placeholder(\"game#name\") === \"Valorant\") {");
        assertThat(js).contains("__output += (\"yes\");");
        assertThat(js).contains("} else {");
        assertThat(js).contains("__output += (\"no\");");
    }

    @Test
    void compilesComparisonOperatorsOtherThanEquality() {
        String js = compiler.compile(parser.parse("{if game#agerating != \"18\"}x{/if}"));
        assertThat(js).contains("!==");
        js = compiler.compile(parser.parse("{if game#agerating <= \"18\"}x{/if}"));
        assertThat(js).contains("<=");
    }

    @Test
    void compilesForEachWithVarRefBody() {
        String js = compiler.compile(parser.parse("{for f in fallbacks}-{f} {/for}"));
        assertThat(js).contains("for (const f of ctx.list(\"fallbacks\")) {");
        assertThat(js).contains("__output += (\"-\");");
        assertThat(js).contains("__output += (f);");
    }

    @Test
    void varDeclaredInsideIfIsBlockScopedByPlainJs() {
        String js = compiler.compile(parser.parse(
            "{if game#name == \"Valorant\"}{var inner = \"x\"}{print inner}{/if}"));
        // `let inner` appears only inside the `if` block — no extra scoping bookkeeping needed,
        // native JS `let`/block scoping means `inner` is not visible outside the `{ }`.
        assertThat(js).contains("if (");
        assertThat(js).contains("let inner = \"x\";");
    }

    @Test
    void compileWithTraceEmitsVarAndBranchHooks() {
        String js = compiler.compileWithTrace(parser.parse(
            "{var msg = \"\"}{if game#name == \"Valorant\"}{msg = msg + \"ranked\"}{/if}{print msg}"));
        assertThat(js).contains("__trace.var(\"msg\", msg);");
        assertThat(js).contains("__trace.branch(");
    }

    @Test
    void compileWithoutTraceEmitsNoHooks() {
        String js = compiler.compile(parser.parse("{var msg = \"\"}{print msg}"));
        assertThat(js).doesNotContain("__trace");
    }

    @Test
    void escapesQuotesAndBackslashesInContextPath() {
        CommandAst ast = new CommandAst(List.of(
            new PrintStatement(new fr.enimaloc.catapult.chat.command.ast.ContextGetExpr("weird\"path\\here"))));
        String js = compiler.compile(ast);
        assertThat(js).contains("ctx.placeholder(\"weird\\\"path\\\\here\")");
    }

    @Test
    void rejectsForEachBindingNameContainingASpace() {
        CommandAst ast = new CommandAst(List.of(new ForEachStatement("weird name", "fallbacks", List.of())));
        assertThatThrownBy(() -> compiler.compile(ast))
            .isInstanceOf(JsCompilationException.class)
            .hasMessageContaining("weird name");
    }

    @Test
    void rejectsInvalidVarRefIdentifier() {
        CommandAst ast = new CommandAst(List.of(new PrintStatement(new VarRefExpr("1bad"))));
        assertThatThrownBy(() -> compiler.compile(ast))
            .isInstanceOf(JsCompilationException.class)
            .hasMessageContaining("1bad");
    }
}
