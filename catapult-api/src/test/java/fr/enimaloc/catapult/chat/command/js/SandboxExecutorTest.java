package fr.enimaloc.catapult.chat.command.js;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxExecutorTest {

    private final SandboxExecutor executor = new SandboxExecutor();

    @Test
    void executesPlaceholderLookups() {
        String js = "let result = \"\"; result += ctx.placeholder(\"game#name\"); return result;";
        String output = executor.execute(js, path -> Map.of("game#name", "Valorant").get(path),
            name -> List.of(), Duration.ofSeconds(2));
        assertThat(output).isEqualTo("Valorant");
    }

    @Test
    void executesListIteration() {
        String js = "let result = \"\"; for (const f of ctx.list(\"fallbacks\")) { result += f; } return result;";
        String output = executor.execute(js, path -> null, name -> List.of("a", "b"), Duration.ofSeconds(2));
        assertThat(output).isEqualTo("ab");
    }

    @Test
    void hasNoFileSystemAccess() {
        String js = "let result = \"\"; "
            + "try { require('fs'); } catch (e) { result = 'blocked'; } return result;";
        String output = executor.execute(js, path -> null, name -> List.of(), Duration.ofSeconds(2));
        assertThat(output).isEqualTo("blocked");
    }

    @Test
    void infiniteLoopIsInterruptedByTimeout() {
        String js = "let result = \"\"; while (true) {} return result;";
        assertThatThrownBy(() -> executor.execute(js, path -> null, name -> List.of(), Duration.ofMillis(200)))
            .isInstanceOf(SandboxExecutionException.class);
    }
}
