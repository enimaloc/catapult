package fr.enimaloc.catapult.chat.command.js;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    /**
     * Regression test for the thread-leak that {@code context.close(true)}
     * fixes on the timeout path (see {@link SandboxExecutor} class javadoc).
     * GraalJS worker threads don't honor plain interruption while spinning in
     * an infinite guest loop, so if force-closing the context ever regresses,
     * repeated timeouts would pile up "pool-N-thread-1" worker threads that
     * never terminate.
     *
     * <p>The assertion polls for the worker-thread count to settle back down
     * rather than comparing an exact snapshot, and matches threads by the
     * {@code ExecutorService}'s default naming pattern rather than an exact
     * count of *all* JVM threads — both choices make the test robust against
     * unrelated threads (GC, JIT compiler, other tests' pools) coming and
     * going concurrently in the same JVM/CI environment.
     */
    @Test
    void repeatedTimeoutsDoNotLeakWorkerThreads() {
        String js = "let result = \"\"; while (true) {} return result;";
        Pattern workerThreadName = Pattern.compile("pool-\\d+-thread-\\d+");

        long baseline = countMatchingThreads(workerThreadName);

        for (int i = 0; i < 8; i++) {
            assertThatThrownBy(() -> executor.execute(js, path -> null, name -> List.of(), Duration.ofMillis(100)))
                .isInstanceOf(SandboxExecutionException.class);
        }

        long settled = awaitThreadCountAt(workerThreadName, baseline, Duration.ofSeconds(5));
        assertThat(settled)
            .as("worker threads left running after 8 timeout cycles (baseline was %d)", baseline)
            .isLessThanOrEqualTo(baseline);
    }

    private long countMatchingThreads(Pattern namePattern) {
        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        return threads.stream().filter(t -> namePattern.matcher(t.getName()).matches()).count();
    }

    /**
     * Polls (briefly, bounded) until the matching thread count drops back to
     * (or below) the baseline, so the test doesn't flake on the exact instant
     * the last worker thread happens to finish unwinding after cancellation.
     */
    private long awaitThreadCountAt(Pattern namePattern, long baseline, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long last;
        do {
            last = countMatchingThreads(namePattern);
            if (last <= baseline) {
                return last;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        } while (System.nanoTime() < deadline);
        return last;
    }
}
