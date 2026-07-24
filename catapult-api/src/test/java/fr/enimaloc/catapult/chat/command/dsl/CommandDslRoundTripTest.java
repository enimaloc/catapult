package fr.enimaloc.catapult.chat.command.dsl;

import fr.enimaloc.catapult.chat.command.ast.AssignStatement;
import fr.enimaloc.catapult.chat.command.ast.BinaryExpr;
import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.ConcatStatement;
import fr.enimaloc.catapult.chat.command.ast.ContextGetExpr;
import fr.enimaloc.catapult.chat.command.ast.ForEachStatement;
import fr.enimaloc.catapult.chat.command.ast.IfStatement;
import fr.enimaloc.catapult.chat.command.ast.LiteralExpr;
import fr.enimaloc.catapult.chat.command.ast.PrintStatement;
import fr.enimaloc.catapult.chat.command.ast.ValueType;
import fr.enimaloc.catapult.chat.command.ast.VarDeclStatement;
import fr.enimaloc.catapult.chat.command.ast.VarRefExpr;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommandDslRoundTripTest {

    private final CommandDslParser parser = new CommandDslParser();
    private final CommandDslGenerator generator = new CommandDslGenerator();

    @Test
    void bareContextPathRoundTrips() {
        // The generator's canonical output for a bare context tag is now "ctx.a.b", not "a#b".
        String source = "Now playing {game#name}!";
        CommandAst ast = parser.parse(source);
        assertThat(parser.parse(generator.generate(ast))).isEqualTo(ast);
        assertThat(generator.generate(ast)).isEqualTo("Now playing {ctx.game.name}!");
    }

    @Test
    void ctxDotChainBareTagRoundTrips() {
        String source = "Now playing {ctx.game.name}!";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void serviceCallWithLiteralArgRoundTrips() {
        String source = "{igdb#getGame(\"Valorant\")}";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void serviceCallWithBarePathArgRoundTrips() {
        // Canonical output for the ContextGetExpr arg is now the "ctx.a.b" dot-chain.
        String source = "{steam#getPrice(game#store#steam)}";
        CommandAst ast = parser.parse(source);
        assertThat(parser.parse(generator.generate(ast))).isEqualTo(ast);
        assertThat(generator.generate(ast)).isEqualTo("{steam#getPrice(ctx.game.store.steam)}");
    }

    @Test
    void varRefBareTagRoundTrips() {
        String source = "{for f in fallbacks}{f}{/for}";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void stringLiteralContainingUnbalancedBraceRoundTrips() {
        // The trailing "{print msg}" compacts to the bare "{msg}" shorthand on generation (see
        // CommandDslGenerator's javadoc), so the AST, not the literal text, is what round-trips
        // here — same pattern as designSpecExampleProgramRoundTrips below.
        String source = "{var msg = \"a}b\"}{print msg}";
        CommandAst ast = parser.parse(source);
        assertThat(parser.parse(generator.generate(ast))).isEqualTo(ast);
    }

    @Test
    void designSpecExampleProgramRoundTrips() {
        // The exact program from docs/specs/2026-07-21-chat-command-block-dsl-design.md
        String source = "{var msg = \"\"}{msg = msg + \"Now playing \"}{msg = msg + get(game#name)}"
            + "{if game#name == \"Valorant\"}{msg = msg + \" (ranked)\"}{/if}"
            + "{for f in fallbacks}{msg = msg + f}{/for}{print msg}";
        CommandAst ast = parser.parse(source);
        assertThat(ast.statements()).hasSize(6);
        // The trailing "{print msg}" is the explicit form of a bare var-ref print, which the
        // generator's canonical output always renders as the compact "{msg}" shorthand (see class
        // javadoc) — so the AST, not the literal text, is what round-trips here.
        assertThat(parser.parse(generator.generate(ast))).isEqualTo(ast);
    }

    @Test
    void generatedAstRoundTripsDirectlyWithoutGoingThroughText() {
        CommandAst ast = new CommandAst(List.of(
            new VarDeclStatement("msg", ValueType.STRING, new LiteralExpr("", ValueType.STRING)),
            new ConcatStatement("msg", new LiteralExpr("hi ", ValueType.STRING)),
            new IfStatement(
                new BinaryExpr(new ContextGetExpr("tw#active"), "!=", new LiteralExpr("", ValueType.STRING)),
                List.of(new ConcatStatement("msg", new ContextGetExpr("tw#active"))),
                List.of(new ConcatStatement("msg", new LiteralExpr("none", ValueType.STRING)))),
            new ForEachStatement("f", "fallbacks", List.of(new ConcatStatement("msg", new VarRefExpr("f")))),
            new AssignStatement("msg", new VarRefExpr("msg")),
            new PrintStatement(new VarRefExpr("msg"))
        ));

        String text = generator.generate(ast);
        assertThat(parser.parse(text)).isEqualTo(ast);
    }

    @Test
    void objectLiteralRoundTrips() {
        String source = "{var game = {name: \"Valorant\", price: 29.99}}";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void nestedObjectLiteralRoundTrips() {
        String source = "{var outer = {a: {b: 1, c: 2}, d: 3}}";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void propertyGetViaGetCallParsesAndGeneratesAsDotChain() {
        // The generator's canonical output for PropertyGetExpr is now a plain dot-chain, not
        // get(obj, "prop") — get(...) with 2 args still parses (never breaks existing commands).
        String source = "{msg = get(game, \"name\")}";
        CommandAst ast = parser.parse(source);
        assertThat(parser.parse(generator.generate(ast))).isEqualTo(ast);
        assertThat(generator.generate(ast)).isEqualTo("{msg = game.name}");
    }

    @Test
    void propertyGetDotChainRoundTrips() {
        String source = "{msg = game.name}";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void generatedObjectAndPropertyGetAstRoundTripsDirectlyWithoutGoingThroughText() {
        Map<String, fr.enimaloc.catapult.chat.command.ast.Expression> props = new java.util.LinkedHashMap<>();
        props.put("name", new LiteralExpr("Valorant", ValueType.STRING));
        props.put("price", new LiteralExpr("29.99", ValueType.NUMBER));
        CommandAst ast = new CommandAst(List.of(
            new VarDeclStatement("game", ValueType.OBJECT,
                new fr.enimaloc.catapult.chat.command.ast.ObjectLiteralExpr(props)),
            new AssignStatement("msg",
                new fr.enimaloc.catapult.chat.command.ast.PropertyGetExpr(new VarRefExpr("game"), "name"))
        ));

        String text = generator.generate(ast);
        assertThat(parser.parse(text)).isEqualTo(ast);
    }

    @Test
    void paramGetRoundTrips() {
        String source = "{msg = ctx.params.language}";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }
}
