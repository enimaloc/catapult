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
        // Every context path the ast references gets a one-time setup line building the real
        // ctx.a.b dot-chain via ctx.placeholder(...); the rest of the body then reads ctx.game.name
        // directly instead of calling ctx.placeholder(...) inline (see JsCompiler's class javadoc).
        String js = compiler.compile(parser.parse("Now playing {game#name}!"));
        assertThat(js).contains("let __output = \"\";");
        assertThat(js).contains("ctx.game = ctx.game || {};");
        assertThat(js).contains("ctx.game.name = ctx.placeholder(\"game#name\");");
        assertThat(js).contains("__output += (\"Now playing \");");
        assertThat(js).contains("__output += (ctx.game.name);");
        assertThat(js).contains("__output += (\"!\");");
        assertThat(js).contains("return __output;");
    }

    @Test
    void compilesVarDeclAssignAndConcat() {
        String js = compiler.compile(parser.parse(
            "{var msg = \"\"}{msg = msg + \"Now playing \"}{msg = msg + get(game#name)}{print msg}"));
        assertThat(js).contains("ctx.game.name = ctx.placeholder(\"game#name\");");
        assertThat(js).contains("let msg = \"\";");
        assertThat(js).contains("msg = msg + (\"Now playing \");");
        assertThat(js).contains("msg = msg + (ctx.game.name);");
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
        assertThat(js).contains("ctx.game.name = ctx.placeholder(\"game#name\");");
        assertThat(js).contains("if (ctx.game.name === \"Valorant\") {");
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
        // A single-segment path (no '#') isn't a safe JS identifier, so the dot-chain setup
        // falls back to bracket notation instead of throwing.
        CommandAst ast = new CommandAst(List.of(
            new PrintStatement(new fr.enimaloc.catapult.chat.command.ast.ContextGetExpr("weird\"path\\here"))));
        String js = compiler.compile(ast);
        assertThat(js).contains("ctx.placeholder(\"weird\\\"path\\\\here\")");
        assertThat(js).contains("ctx[\"weird\\\"path\\\\here\"]");
    }

    @Test
    void sharedPathPrefixGetsOnlyOneSetupLine() {
        String js = compiler.compile(parser.parse(
            "{if game#store#steam == \"\"}{print game#store#xbox}{/if}"));
        assertThat(js).containsOnlyOnce("ctx.game = ctx.game || {};");
        assertThat(js).containsOnlyOnce("ctx.game.store = ctx.game.store || {};");
        assertThat(js).contains("ctx.game.store.steam = ctx.placeholder(\"game#store#steam\");");
        assertThat(js).contains("ctx.game.store.xbox = ctx.placeholder(\"game#store#xbox\");");
    }

    @Test
    void ctxDotChainResolvesTheRealPlaceholderValueEndToEndInTheSandbox() {
        // Proves the compile-time-lowering (ctx.game.name reading a value the setup preamble
        // resolved via ctx.placeholder) actually works at runtime, not just in the JS text.
        String js = compiler.compile(parser.parse("Now playing {ctx.game.name}!"));
        String result = new SandboxExecutor().execute(js,
            path -> path.equals("game#name") ? "Valorant" : null, name -> List.of(),
            java.time.Duration.ofSeconds(2));
        assertThat(result).isEqualTo("Now playing Valorant!");
    }

    @Test
    void rejectsProtoPathSegmentAsAPrototypePollutionRisk() {
        CommandAst ast = new CommandAst(List.of(
            new PrintStatement(new fr.enimaloc.catapult.chat.command.ast.ContextGetExpr("__proto__#name"))));
        assertThatThrownBy(() -> compiler.compile(ast))
            .isInstanceOf(JsCompilationException.class)
            .hasMessageContaining("__proto__");
    }

    @Test
    void rejectsConstructorAndPrototypePathSegments() {
        for (String unsafe : new String[]{"constructor", "prototype"}) {
            CommandAst ast = new CommandAst(List.of(
                new PrintStatement(new fr.enimaloc.catapult.chat.command.ast.ContextGetExpr("game#" + unsafe))));
            assertThatThrownBy(() -> compiler.compile(ast))
                .isInstanceOf(JsCompilationException.class)
                .hasMessageContaining(unsafe);
        }
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

    @Test
    void compilesObjectLiteralAsAJsObjectAndPropertyGetAsBracketAccess() {
        String js = compiler.compile(parser.parse(
            "{var game = {name: \"Valorant\", price: 29.99}}{msg = get(game, \"name\")}"));
        assertThat(js).contains("let game = {\"name\": \"Valorant\", \"price\": 29.99};");
        assertThat(js).contains("msg = game[\"name\"];");
    }

    @Test
    void executesObjectLiteralAndPropertyGetEndToEndInTheRealSandbox() {
        // Proves the whole pipeline (parse -> compile -> real GraalJS execution), not just
        // that the generated JS text looks right.
        String js = compiler.compile(parser.parse(
            "{var game = {name: \"Valorant\", price: \"free\"}}"
            + "{var msg = \"\"}{msg = msg + get(game, \"name\")}{msg = msg + \" - \"}"
            + "{msg = msg + get(game, \"price\")}{print msg}"));

        String result = new SandboxExecutor().execute(js, path -> null, name -> List.of(),
            java.time.Duration.ofSeconds(2));

        assertThat(result).isEqualTo("Valorant - free");
    }

    @Test
    void compilesParamGetWithASetupLineLikeContextGet() {
        String js = compiler.compile(parser.parse("{msg = ctx.params.language}"));
        assertThat(js).contains("ctx.params = ctx.params || {};");
        assertThat(js).contains("ctx.params.language = ctx.param(\"language\");");
        assertThat(js).contains("msg = ctx.params.language;");
    }

    @Test
    void sharedParamKeyPrefixGetsOnlyOneSetupLine() {
        String js = compiler.compile(parser.parse(
            "{if ctx.params.language == \"fr\"}{print ctx.params.region}{/if}"));
        assertThat(js).containsOnlyOnce("ctx.params = ctx.params || {};");
        assertThat(js).contains("ctx.params.language = ctx.param(\"language\");");
        assertThat(js).contains("ctx.params.region = ctx.param(\"region\");");
    }

    @Test
    void paramKeyResolvesEndToEndInTheRealSandbox() {
        String js = compiler.compile(parser.parse("Lang: {ctx.params.language}"));
        String result = new SandboxExecutor().execute(js, path -> null, name -> List.of(), null, null,
            key -> "language".equals(key) ? "fr" : null, java.time.Duration.ofSeconds(2));
        assertThat(result).isEqualTo("Lang: fr");
    }

    @Test
    void rejectsProtoParamKeyAsAPrototypePollutionRisk() {
        CommandAst ast = new CommandAst(List.of(
            new PrintStatement(new fr.enimaloc.catapult.chat.command.ast.ParamGetExpr("__proto__"))));
        assertThatThrownBy(() -> compiler.compile(ast))
            .isInstanceOf(JsCompilationException.class)
            .hasMessageContaining("__proto__");
    }
}
