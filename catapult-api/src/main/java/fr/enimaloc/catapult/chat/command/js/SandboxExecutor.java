package fr.enimaloc.catapult.chat.command.js;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs compiled command JS in a GraalJS sandbox with no filesystem/network/
 * host-class access — the only way out to the outside world is the
 * whitelisted `ctx` object bound per execution.
 *
 * <p>Timeouts are enforced by force-closing the underlying GraalJS
 * {@link Context} ({@code close(true)}) from the calling thread rather than
 * merely abandoning the {@link Future}. GraalJS threads do not honor plain
 * {@link Thread#interrupt()} while spinning in guest code (e.g. an infinite
 * {@code while (true) {}} loop never yields back to the JVM), so relying on
 * {@code ExecutorService#shutdownNow()} alone would leave the worker thread
 * running forever — a genuine resource leak. {@code Context.close(true)} asks
 * the Graal runtime to cancel the currently executing guest code at its next
 * safepoint (loop back-edges, method calls, etc.), which reliably unwinds the
 * infinite loop and lets the worker thread terminate.
 */
public class SandboxExecutor {

    public interface PlaceholderContext {
        String resolve(String path);
    }

    public interface ListContext {
        List<String> resolveList(String name);
    }

    public String execute(String compiledJs, PlaceholderContext placeholders, ListContext lists, Duration timeout) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Context context = buildContext();
        try {
            Future<String> future = executor.submit(() -> runInContext(context, compiledJs, placeholders, lists));
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // Force-cancel the guest execution so the worker thread actually
                // unwinds instead of spinning forever in the background.
                context.close(true);
                awaitWorkerTermination(future);
                throw new SandboxExecutionException("Command execution timed out after " + timeout, e);
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new SandboxExecutionException("Command execution failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxExecutionException("Command execution interrupted", e);
        } finally {
            executor.shutdownNow();
            closeQuietly(context);
        }
    }

    /**
     * Waits (briefly, bounded) for the worker thread to actually observe the
     * cancellation and unwind, so we don't just fire-and-forget the close
     * call and hope for the best.
     */
    private void awaitWorkerTermination(Future<String> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Expected: the worker thread throws once the context is cancelled.
        }
    }

    private void closeQuietly(Context context) {
        try {
            context.close(true);
        } catch (Exception ignored) {
            // Already closed by the timeout path, or execution finished cleanly.
        }
    }

    private Context buildContext() {
        return Context.newBuilder("js")
            .allowHostAccess(HostAccess.EXPLICIT)
            .allowIO(IOAccess.NONE)
            .allowCreateThread(false)
            .allowNativeAccess(false)
            .build();
    }

    private String runInContext(Context context, String compiledJs, PlaceholderContext placeholders, ListContext lists) {
        try {
            Value bindings = context.getBindings("js");
            Value ctx = context.eval("js", "({})");
            ctx.putMember("placeholder", (ProxyExecutable) args -> placeholders.resolve(args[0].asString()));
            ctx.putMember("list", (ProxyExecutable) args ->
                ProxyArray.fromList(new ArrayList<Object>(lists.resolveList(args[0].asString()))));
            ctx.putMember("call", (ProxyExecutable) args -> {
                throw new UnsupportedOperationException(
                    "service-call binding is wired in Task 11 (per-user context)");
            });
            bindings.putMember("ctx", ctx);

            Value fn = context.eval("js", "(function() {\n" + compiledJs + "\n})");
            Value result = fn.execute();
            return result.isNull() ? "" : result.asString();
        } catch (PolyglotException e) {
            throw new SandboxExecutionException("Command script error: " + e.getMessage(), e);
        }
    }
}
