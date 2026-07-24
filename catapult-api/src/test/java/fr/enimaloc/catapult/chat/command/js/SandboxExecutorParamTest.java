package fr.enimaloc.catapult.chat.command.js;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxExecutorParamTest {

    private final SandboxExecutor executor = new SandboxExecutor();

    @Test
    void ctxParamResolvesTheBoundValue() {
        String js = "return ctx.param(\"language\");";
        String output = executor.execute(js, path -> null, name -> List.of(), null, null,
            key -> "language".equals(key) ? "fr" : null, Duration.ofSeconds(2));

        assertThat(output).isEqualTo("fr");
    }

    @Test
    void ctxParamReturnsNullForAnUnknownKey() {
        String js = "let v = ctx.param(\"missing\"); return v === null ? \"was-null\" : v;";
        String output = executor.execute(js, path -> null, name -> List.of(), null, null,
            key -> null, Duration.ofSeconds(2));

        assertThat(output).isEqualTo("was-null");
    }
}
