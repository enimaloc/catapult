package fr.enimaloc.catapult.chat.command.dsl;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.ContextGetExpr;
import fr.enimaloc.catapult.chat.command.ast.LiteralExpr;
import fr.enimaloc.catapult.chat.command.ast.ObjectLiteralExpr;
import fr.enimaloc.catapult.chat.command.ast.PrintStatement;
import fr.enimaloc.catapult.chat.command.ast.PropertyGetExpr;
import fr.enimaloc.catapult.chat.command.ast.ServiceCallExpr;
import fr.enimaloc.catapult.chat.command.ast.ValueType;
import fr.enimaloc.catapult.chat.command.ast.VarDeclStatement;
import fr.enimaloc.catapult.chat.command.ast.VarRefExpr;
import fr.enimaloc.catapult.chat.command.ast.AssignStatement;
import fr.enimaloc.catapult.chat.command.ast.ConcatStatement;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandDslParserTest {

    private final CommandDslParser parser = new CommandDslParser();

    @Test
    void parsesLiteralTextOnly() {
        CommandAst ast = parser.parse("Hello world");
        assertThat(ast.statements()).containsExactly(
            new PrintStatement(new LiteralExpr("Hello world", ValueType.STRING)));
    }

    @Test
    void parsesLiteralAndBareContextPathShorthand() {
        CommandAst ast = parser.parse("Now playing {game#name}!");
        assertThat(ast.statements()).containsExactly(
            new PrintStatement(new LiteralExpr("Now playing ", ValueType.STRING)),
            new PrintStatement(new ContextGetExpr("game#name")),
            new PrintStatement(new LiteralExpr("!", ValueType.STRING)));
    }

    @Test
    void legacyDotSeparatedPathIsNormalizedToHashSeparated() {
        // Pre-V59__placeholder_hash_separator.sql templates used '.' (e.g. {game.name}); the
        // migration rewrote stored rows once but isn't guaranteed to have caught every one, so
        // the parser tolerates the old separator instead of throwing "Malformed tag".
        CommandAst ast = parser.parse("Now playing {game.name}!");
        assertThat(ast.statements()).containsExactly(
            new PrintStatement(new LiteralExpr("Now playing ", ValueType.STRING)),
            new PrintStatement(new ContextGetExpr("game#name")),
            new PrintStatement(new LiteralExpr("!", ValueType.STRING)));
    }

    @Test
    void legacyDotSeparatedPathWithMultipleSegmentsIsNormalized() {
        CommandAst ast = parser.parse("{game.store.steam}");
        assertThat(ast.statements()).containsExactly(
            new PrintStatement(new ContextGetExpr("game#store#steam")));
    }

    @Test
    void legacyDotSeparatedPathInIfConditionIsNormalized() {
        CommandAst ast = parser.parse("{if game.name == \"Valorant\"}yes{/if}");
        var ifStatement = (fr.enimaloc.catapult.chat.command.ast.IfStatement) ast.statements().get(0);
        assertThat(ifStatement.condition().left()).isEqualTo(new ContextGetExpr("game#name"));
    }

    @Test
    void bareTagWithoutHashIsAVarRefNotAContextGet() {
        CommandAst ast = parser.parse("{f}");
        assertThat(ast.statements()).containsExactly(new PrintStatement(new VarRefExpr("f")));
    }

    @Test
    void parsesVarDeclWithStringLiteralInit() {
        CommandAst ast = parser.parse("{var msg = \"\"}");
        assertThat(ast.statements()).containsExactly(
            new VarDeclStatement("msg", ValueType.STRING, new LiteralExpr("", ValueType.STRING)));
    }

    @Test
    void parsesConcatStatement() {
        CommandAst ast = parser.parse("{msg = msg + \"Now playing \"}");
        assertThat(ast.statements()).containsExactly(
            new ConcatStatement("msg", new LiteralExpr("Now playing ", ValueType.STRING)));
    }

    @Test
    void parsesConcatStatementWithContextGet() {
        CommandAst ast = parser.parse("{msg = msg + get(game#name)}");
        assertThat(ast.statements()).containsExactly(
            new ConcatStatement("msg", new ContextGetExpr("game#name")));
    }

    @Test
    void parsesPlainAssignStatement() {
        CommandAst ast = parser.parse("{msg = \"reset\"}");
        assertThat(ast.statements()).containsExactly(
            new AssignStatement("msg", new LiteralExpr("reset", ValueType.STRING)));
    }

    @Test
    void parsesExplicitPrintStatement() {
        CommandAst ast = parser.parse("{print msg}");
        assertThat(ast.statements()).containsExactly(new PrintStatement(new VarRefExpr("msg")));
    }

    @Test
    void parsesServiceCallWithLiteralArgumentAsBareTagShorthand() {
        CommandAst ast = parser.parse("{igdb#getGame(\"Valorant\")}");
        assertThat(ast.statements()).hasSize(1);
        PrintStatement print = (PrintStatement) ast.statements().get(0);
        ServiceCallExpr call = (ServiceCallExpr) print.expr();
        assertThat(call.namespace()).isEqualTo("igdb");
        assertThat(call.function()).isEqualTo("getGame");
        assertThat(call.args()).containsExactly(new LiteralExpr("Valorant", ValueType.STRING));
    }

    @Test
    void parsesServiceCallWithBarePathArgument() {
        CommandAst ast = parser.parse("{steam#getPrice(game#store#steam)}");
        PrintStatement print = (PrintStatement) ast.statements().get(0);
        ServiceCallExpr call = (ServiceCallExpr) print.expr();
        assertThat(call.args()).containsExactly(new ContextGetExpr("game#store#steam"));
    }

    @Test
    void throwsOnUnclosedBrace() {
        assertThatThrownBy(() -> parser.parse("Hello {world"))
            .isInstanceOf(CommandDslParseException.class);
    }

    @Test
    void parsesServiceCallWithLiteralArgumentContainingComma() {
        CommandAst ast = parser.parse("{igdb#getGame(\"Half-Life, part 2\")}");
        PrintStatement print = (PrintStatement) ast.statements().get(0);
        ServiceCallExpr call = (ServiceCallExpr) print.expr();
        assertThat(call.args()).containsExactly(new LiteralExpr("Half-Life, part 2", ValueType.STRING));
    }

    @Test
    void stringLiteralContainingUnbalancedBraceDoesNotDesyncTagScanning() {
        CommandAst ast = parser.parse("{var msg = \"a}b\"}{print msg}");
        assertThat(ast.statements()).containsExactly(
            new VarDeclStatement("msg", ValueType.STRING, new LiteralExpr("a}b", ValueType.STRING)),
            new PrintStatement(new VarRefExpr("msg")));
    }

    @Test
    void bareContextPathWithInlineFallbackDropsTheFallbackText() {
        // Matches the pre-existing behavior: the DB-configured ChatCommandFallback is what's
        // actually consulted at runtime, not this inline text — see DynamicChatCommand.
        CommandAst ast = parser.parse("{game#name|no game}");
        assertThat(ast.statements()).containsExactly(new PrintStatement(new ContextGetExpr("game#name")));
    }

    @Test
    void parsesObjectLiteralGroupingSeveralPrimitiveValues() {
        CommandAst ast = parser.parse("{var game = {name: \"Valorant\", price: 29.99, free: true}}");
        Map<String, fr.enimaloc.catapult.chat.command.ast.Expression> expected = new LinkedHashMap<>();
        expected.put("name", new LiteralExpr("Valorant", ValueType.STRING));
        expected.put("price", new LiteralExpr("29.99", ValueType.NUMBER));
        expected.put("free", new LiteralExpr("true", ValueType.BOOLEAN));
        assertThat(ast.statements()).containsExactly(
            new VarDeclStatement("game", ValueType.OBJECT, new ObjectLiteralExpr(expected)));
    }

    @Test
    void parsesEmptyObjectLiteral() {
        CommandAst ast = parser.parse("{var obj = {}}");
        assertThat(ast.statements()).containsExactly(
            new VarDeclStatement("obj", ValueType.OBJECT, new ObjectLiteralExpr(Map.of())));
    }

    @Test
    void parsesNestedObjectLiteralWithoutMissplittingTheInnerCommas() {
        // splitTopLevelArgs must track brace depth, not just quotes — otherwise the inner
        // object's own comma (between b and c) would be mistaken for a top-level separator.
        CommandAst ast = parser.parse("{var outer = {a: {b: 1, c: 2}, d: 3}}");
        VarDeclStatement decl = (VarDeclStatement) ast.statements().get(0);
        ObjectLiteralExpr outer = (ObjectLiteralExpr) decl.init();
        assertThat(outer.properties()).containsOnlyKeys("a", "d");
        ObjectLiteralExpr inner = (ObjectLiteralExpr) outer.properties().get("a");
        assertThat(inner.properties()).containsOnlyKeys("b", "c");
        assertThat(inner.properties().get("b")).isEqualTo(new LiteralExpr("1", ValueType.NUMBER));
        assertThat(inner.properties().get("c")).isEqualTo(new LiteralExpr("2", ValueType.NUMBER));
    }

    @Test
    void parsesPropertyGetOnAnObjectVariable() {
        CommandAst ast = parser.parse("{msg = get(game, \"name\")}");
        assertThat(ast.statements()).containsExactly(
            new AssignStatement("msg", new PropertyGetExpr(new VarRefExpr("game"), "name")));
    }

    @Test
    void objectPropertyAccessDoesNotCollideWithLegacyDotPathNormalization() {
        // get(x, "y") is a dedicated 2-arg form, distinct from get(path)'s 1-arg context read —
        // no '.' syntax is introduced, so there is no ambiguity with legacy dot-paths to resolve.
        CommandAst ast = parser.parse("{msg = get(game, \"store\")}");
        AssignStatement assign = (AssignStatement) ast.statements().get(0);
        assertThat(assign.expr()).isEqualTo(new PropertyGetExpr(new VarRefExpr("game"), "store"));
    }

    @Test
    void malformedObjectPropertyThrows() {
        assertThatThrownBy(() -> parser.parse("{var x = {notAKeyValuePair}}"))
            .isInstanceOf(CommandDslParseException.class);
    }
}
