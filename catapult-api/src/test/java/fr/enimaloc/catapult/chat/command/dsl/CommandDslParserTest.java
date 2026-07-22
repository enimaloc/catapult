package fr.enimaloc.catapult.chat.command.dsl;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.ContextGetExpr;
import fr.enimaloc.catapult.chat.command.ast.LiteralExpr;
import fr.enimaloc.catapult.chat.command.ast.PrintStatement;
import fr.enimaloc.catapult.chat.command.ast.ServiceCallExpr;
import fr.enimaloc.catapult.chat.command.ast.ValueType;
import fr.enimaloc.catapult.chat.command.ast.VarDeclStatement;
import fr.enimaloc.catapult.chat.command.ast.VarRefExpr;
import fr.enimaloc.catapult.chat.command.ast.AssignStatement;
import fr.enimaloc.catapult.chat.command.ast.ConcatStatement;
import org.junit.jupiter.api.Test;

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
    void bareContextPathWithInlineFallbackDropsTheFallbackText() {
        // Matches the pre-existing behavior: the DB-configured ChatCommandFallback is what's
        // actually consulted at runtime, not this inline text — see DynamicChatCommand.
        CommandAst ast = parser.parse("{game#name|no game}");
        assertThat(ast.statements()).containsExactly(new PrintStatement(new ContextGetExpr("game#name")));
    }
}
