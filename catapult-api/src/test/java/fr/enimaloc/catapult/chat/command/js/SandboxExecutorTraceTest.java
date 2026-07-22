package fr.enimaloc.catapult.chat.command.js;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.chat.command.trace.ExecutionTrace;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxExecutorTraceTest {

    private final SandboxExecutor executor = new SandboxExecutor();

    @Test
    void traceRecordsEachPlaceholderResolution() {
        String js = "let result = \"\"; "
            + "result += ctx.placeholder(\"game#name\"); "
            + "result += \"!\"; return result;";

        ExecutionTrace trace = executor.executeWithTrace(js,
            path -> "Valorant", name -> List.of(), null, Duration.ofSeconds(2));

        assertThat(trace.finalOutput()).isEqualTo("Valorant!");
        assertThat(trace.entries()).anySatisfy(entry -> {
            assertThat(entry.nodeType()).isEqualTo("placeholder");
            assertThat(entry.resolvedValue()).isEqualTo("Valorant");
            assertThat(entry.error()).isFalse();
        });
    }

    @Test
    void traceRecordsErrorWhenServiceCallFails() {
        String js = "let result = \"\"; result += ctx.call(\"steam\", \"getPrice\", \"999\"); return result;";
        ServiceFunctionRegistry registry = new ServiceFunctionRegistry();
        // no functions registered -> lookup fails -> traced as an error entry

        ExecutionTrace trace = executor.executeWithTrace(js, path -> null, name -> List.of(),
            registry, Duration.ofSeconds(2));

        assertThat(trace.entries()).anySatisfy(entry -> assertThat(entry.error()).isTrue());
    }
}
