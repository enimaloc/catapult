package fr.enimaloc.catapult.chat.command.js;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.chat.command.trace.ExecutionTrace;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxExecutorTraceTest {

    private final SandboxExecutor executor = new SandboxExecutor();
    private final CommandDslParser parser = new CommandDslParser();
    private final JsCompiler compiler = new JsCompiler();

    @Test
    void traceRecordsEachPlaceholderResolution() {
        String js = "let __output = \"\"; "
            + "__output += ctx.placeholder(\"game#name\"); "
            + "__output += \"!\"; return __output;";

        ExecutionTrace trace = executor.executeWithTrace(js,
            path -> "Valorant", name -> List.of(), null, null, null, Duration.ofSeconds(2));

        assertThat(trace.finalOutput()).isEqualTo("Valorant!");
        assertThat(trace.entries()).anySatisfy(entry -> {
            assertThat(entry.nodeType()).isEqualTo("placeholder");
            assertThat(entry.resolvedValue()).isEqualTo("Valorant");
            assertThat(entry.error()).isFalse();
        });
    }

    @Test
    void traceRecordsErrorWhenServiceCallFails() {
        String js = "let __output = \"\"; __output += ctx.call(\"steam\", \"getPrice\", \"999\"); return __output;";
        ServiceFunctionRegistry registry = new ServiceFunctionRegistry();

        ExecutionTrace trace = executor.executeWithTrace(js, path -> null, name -> List.of(),
            registry, null, null, Duration.ofSeconds(2));

        assertThat(trace.entries()).anySatisfy(entry -> assertThat(entry.error()).isTrue());
    }

    @Test
    void traceRecordsVariableStateAfterEachStatement() {
        CommandAst ast = parser.parse("{var msg = \"\"}{msg = msg + \"Now playing \"}{msg = msg + get(game#name)}{print msg}");
        String js = compiler.compileWithTrace(ast);

        ExecutionTrace trace = executor.executeWithTrace(js,
            path -> "Valorant", name -> List.of(), null, null, null, Duration.ofSeconds(2));

        assertThat(trace.finalOutput()).isEqualTo("Now playing Valorant");
        List<String> varValues = trace.entries().stream()
            .filter(e -> "var".equals(e.nodeType()) && "msg".equals(e.description()))
            .map(e -> e.resolvedValue())
            .toList();
        assertThat(varValues).containsExactly("", "Now playing ", "Now playing Valorant");
    }

    @Test
    void traceRecordsWhichIfBranchWasTakenAndWhy() {
        CommandAst ast = parser.parse(
            "{if game#name == \"Valorant\"}{print \"ranked\"}{else}{print \"casual\"}{/if}");
        String js = compiler.compileWithTrace(ast);

        ExecutionTrace trace = executor.executeWithTrace(js,
            path -> "Valorant", name -> List.of(), null, null, null, Duration.ofSeconds(2));

        assertThat(trace.finalOutput()).isEqualTo("ranked");
        assertThat(trace.entries()).anySatisfy(entry -> {
            assertThat(entry.nodeType()).isEqualTo("if-branch");
            assertThat(entry.resolvedValue()).isEqualTo("then");
        });
    }
}
