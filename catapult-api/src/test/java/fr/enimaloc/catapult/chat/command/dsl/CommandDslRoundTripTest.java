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

import static org.assertj.core.api.Assertions.assertThat;

class CommandDslRoundTripTest {

    private final CommandDslParser parser = new CommandDslParser();
    private final CommandDslGenerator generator = new CommandDslGenerator();

    @Test
    void bareContextPathRoundTrips() {
        String source = "Now playing {game#name}!";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void serviceCallWithLiteralArgRoundTrips() {
        String source = "{igdb#getGame(\"Valorant\")}";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void serviceCallWithBarePathArgRoundTrips() {
        String source = "{steam#getPrice(game#store#steam)}";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void varRefBareTagRoundTrips() {
        String source = "{for f in fallbacks}{f}{/for}";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void designSpecExampleProgramRoundTrips() {
        // The exact program from docs/specs/2026-07-21-chat-command-block-dsl-design.md
        String source = "{var msg = \"\"}{msg = msg + \"Now playing \"}{msg = msg + get(game#name)}"
            + "{if game#name == \"Valorant\"}{msg = msg + \" (ranked)\"}{/if}"
            + "{for f in fallbacks}{msg = msg + f}{/for}{print msg}";
        CommandAst ast = parser.parse(source);
        assertThat(ast.statements()).hasSize(6);
        assertThat(generator.generate(ast)).isEqualTo(source);
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
}
