package fr.enimaloc.catapult.chat.command.dsl;

import fr.enimaloc.catapult.chat.command.ast.BinaryExpr;
import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.ContextGetExpr;
import fr.enimaloc.catapult.chat.command.ast.ForEachStatement;
import fr.enimaloc.catapult.chat.command.ast.IfStatement;
import fr.enimaloc.catapult.chat.command.ast.LiteralExpr;
import fr.enimaloc.catapult.chat.command.ast.PrintStatement;
import fr.enimaloc.catapult.chat.command.ast.ValueType;
import fr.enimaloc.catapult.chat.command.ast.VarRefExpr;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandDslControlFlowTest {

    private final CommandDslParser parser = new CommandDslParser();
    private final CommandDslGenerator generator = new CommandDslGenerator();

    @Test
    void parsesIfElse() {
        CommandAst ast = parser.parse("{if game#name == \"Valorant\"}yes{else}no{/if}");
        IfStatement ifStatement = (IfStatement) ast.statements().get(0);
        assertThat(ifStatement.condition()).isEqualTo(new BinaryExpr(
            new ContextGetExpr("game#name"), "==", new LiteralExpr("Valorant", ValueType.STRING)));
        assertThat(ifStatement.thenBranch()).containsExactly(
            new PrintStatement(new LiteralExpr("yes", ValueType.STRING)));
        assertThat(ifStatement.elseBranch()).containsExactly(
            new PrintStatement(new LiteralExpr("no", ValueType.STRING)));
    }

    @Test
    void parsesIfWithAllSixComparisonOperators() {
        for (String op : new String[]{"==", "!=", "<", ">", "<=", ">="}) {
            CommandAst ast = parser.parse("{if game#agerating " + op + " \"18\"}x{/if}");
            IfStatement ifStatement = (IfStatement) ast.statements().get(0);
            assertThat(ifStatement.condition().operator()).isEqualTo(op);
        }
    }

    @Test
    void parsesForEachOverFallbacksWithVarRefBody() {
        CommandAst ast = parser.parse("{for f in fallbacks}-{f} {/for}");
        ForEachStatement forStatement = (ForEachStatement) ast.statements().get(0);
        assertThat(forStatement.bindingName()).isEqualTo("f");
        assertThat(forStatement.listSource()).isEqualTo("fallbacks");
        assertThat(forStatement.body()).containsExactly(
            new PrintStatement(new LiteralExpr("-", ValueType.STRING)),
            new PrintStatement(new VarRefExpr("f")),
            new PrintStatement(new LiteralExpr(" ", ValueType.STRING))
        );
    }

    @Test
    void ifElseRoundTrips() {
        String source = "{if game#name == \"Valorant\"}yes{else}no{/if}";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void forEachRoundTrips() {
        String source = "{for f in fallbacks}-{f} {/for}";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void ifWithoutElseRoundTrips() {
        String source = "{if game#agerating == \"18\"}yes{/if}";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void nestedIfInsideForEachRoundTripsAndScopesCorrectly() {
        String source = "{for f in fallbacks}{if f == \"none\"}skip{else}{f}{/if}{/for}";
        CommandAst ast = parser.parse(source);
        assertThat(generator.generate(ast)).isEqualTo(source);
        ForEachStatement forStatement = (ForEachStatement) ast.statements().get(0);
        IfStatement nested = (IfStatement) forStatement.body().get(0);
        // "f" inside the for-each body condition is the loop binding (VarRefExpr), not a context path
        assertThat(nested.condition().left()).isEqualTo(new VarRefExpr("f"));
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
