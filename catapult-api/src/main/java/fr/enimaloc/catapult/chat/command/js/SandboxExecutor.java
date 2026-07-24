package fr.enimaloc.catapult.chat.command.js;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.chat.command.trace.ExecutionTrace;
import fr.enimaloc.catapult.chat.command.trace.TraceEntry;
import fr.enimaloc.catapult.domain.UserAccount;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
@Slf4j
@Component
public class SandboxExecutor {

    public interface PlaceholderContext {
        String resolve(String path);
    }

    public interface ListContext {
        List<String> resolveList(String name);
    }

    public interface SettingContext {
        String resolve(String key);
    }

    /**
     * Pays the one-time GraalJS engine/class-loading cold-start cost (which can run into
     * multiple seconds on a JVM without JVMCI, i.e. the interpreter-only fallback runtime) at
     * application startup rather than on the first real command execution. Without this, the
     * first chat command dispatched after boot risks tripping its own {@code timeout} budget on
     * nothing but JVM warm-up, not actual script work — best-effort: failures are logged and
     * swallowed since a failed warm-up must not prevent the app from starting.
     */
    @PostConstruct
    void warmUp() {
        try {
            execute("return \"\";", path -> "", name -> List.of(), Duration.ofSeconds(30));
        } catch (RuntimeException e) {
            log.warn("GraalJS sandbox warm-up failed (first real command may pay the cold-start cost): {}",
                e.getMessage());
        }
    }

    public String execute(String compiledJs, PlaceholderContext placeholders, ListContext lists, Duration timeout) {
        return execute(compiledJs, placeholders, lists, null, null, null, timeout);
    }

    /**
     * Same as {@link #execute(String, PlaceholderContext, ListContext, Duration)}
     * but also binds {@code ctx.call(namespace, function, ...args)} to the
     * given registry's whitelisted service functions. A {@code null} registry
     * is accepted for callers that don't need service calls; {@code ctx.call}
     * then fails loudly only if the script actually invokes it. {@code user} is
     * the invoking streamer, passed straight through to {@link ServiceFunction#invoke} —
     * {@code null} is accepted the same way a null registry is, for callers that don't
     * have one.
     */
    public String execute(String compiledJs, PlaceholderContext placeholders, ListContext lists,
                           ServiceFunctionRegistry registry, UserAccount user, SettingContext settings, Duration timeout) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Context context = buildContext();
        try {
            Future<String> future = executor.submit(() -> runInContext(context, compiledJs, placeholders, lists, registry, user, settings));
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                closeQuietly(context);
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
     * Same as {@link #execute(String, PlaceholderContext, ListContext, ServiceFunctionRegistry, UserAccount, Duration)}
     * but also records a step-by-step {@link ExecutionTrace} of every placeholder resolution,
     * list iteration and service call made during the run — used by the command editor's
     * "Tester" button so authors can see why a command produced (or failed to produce) a
     * given output.
     */
    public ExecutionTrace executeWithTrace(String compiledJs, PlaceholderContext placeholders, ListContext lists,
                                            ServiceFunctionRegistry registry, UserAccount user, SettingContext settings,
                                            Duration timeout) {
        ExecutionTrace trace = new ExecutionTrace();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Context context = buildContext();
        try {
            Future<String> future = executor.submit(() ->
                runInContextWithTrace(context, compiledJs, placeholders, lists, registry, user, settings, trace));
            try {
                String output = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                trace.finish(output);
                return trace;
            } catch (TimeoutException e) {
                closeQuietly(context);
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

    private String runInContext(Context context, String compiledJs, PlaceholderContext placeholders, ListContext lists,
                                 ServiceFunctionRegistry registry, UserAccount user, SettingContext settings) {
        try {
            Value bindings = context.getBindings("js");
            Value ctx = context.eval("js", "({})");
            ctx.putMember("placeholder", (ProxyExecutable) args -> placeholders.resolve(args[0].asString()));
            ctx.putMember("list", (ProxyExecutable) args ->
                ProxyArray.fromList(new ArrayList<Object>(lists.resolveList(args[0].asString()))));
            ctx.putMember("setting", (ProxyExecutable) args -> settings == null ? null : settings.resolve(args[0].asString()));
            ctx.putMember("call", (ProxyExecutable) args -> {
                if (registry == null) {
                    throw new UnsupportedOperationException(
                        "No ServiceFunctionRegistry bound for this execution");
                }
                if (args.length < 2) {
                    throw new IllegalArgumentException("ctx.call requires a namespace and a function name");
                }
                String namespace = args[0].asString();
                String function = args[1].asString();
                Object[] callArgs = new Object[args.length - 2];
                for (int i = 2; i < args.length; i++) {
                    callArgs[i - 2] = args[i].as(Object.class);
                }
                try {
                    return registry.lookup(namespace, function)
                        .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown service function " + namespace + "#" + function))
                        .invoke(user, callArgs);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException("Service function " + namespace + "#" + function + " failed", e);
                }
            });
            bindings.putMember("ctx", ctx);

            Value fn = context.eval("js", "(function() {\n" + compiledJs + "\n})");
            Value result = fn.execute();
            return result.isNull() ? "" : result.asString();
        } catch (PolyglotException e) {
            throw new SandboxExecutionException("Command script error: " + e.getMessage(), e);
        }
    }

    private String runInContextWithTrace(Context context, String compiledJs, PlaceholderContext placeholders,
                                          ListContext lists, ServiceFunctionRegistry registry, UserAccount user,
                                          SettingContext settings, ExecutionTrace trace) {
        try {
            Value bindings = context.getBindings("js");
            Value ctx = context.eval("js", "({})");
            ctx.putMember("placeholder", (ProxyExecutable) args -> {
                String path = args[0].asString();
                String value = placeholders.resolve(path);
                trace.record(new TraceEntry("placeholder", "resolve " + path, value, false));
                return value;
            });
            ctx.putMember("list", (ProxyExecutable) args -> {
                String name = args[0].asString();
                List<String> values = lists.resolveList(name);
                trace.record(new TraceEntry("for-each", "iterate " + name, String.join(", ", values), false));
                return ProxyArray.fromList(new ArrayList<Object>(values));
            });
            ctx.putMember("setting", (ProxyExecutable) args -> {
                String key = args[0].asString();
                String value = settings == null ? null : settings.resolve(key);
                trace.record(new TraceEntry("setting", "resolve " + key, value, false));
                return value;
            });
            ctx.putMember("call", (ProxyExecutable) args -> {
                if (args.length < 2) {
                    throw new IllegalArgumentException("ctx.call requires a namespace and a function name");
                }
                String namespace = args[0].asString();
                String function = args[1].asString();
                Object[] callArgs = new Object[args.length - 2];
                for (int i = 2; i < args.length; i++) {
                    callArgs[i - 2] = args[i].as(Object.class);
                }
                try {
                    if (registry == null) {
                        throw new UnsupportedOperationException("No ServiceFunctionRegistry bound for this execution");
                    }
                    Object value = registry.lookup(namespace, function)
                        .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown service function " + namespace + "#" + function))
                        .invoke(user, callArgs);
                    trace.record(new TraceEntry("service-call", namespace + "#" + function,
                        String.valueOf(value), false));
                    return value;
                } catch (RuntimeException e) {
                    trace.record(new TraceEntry("service-call", namespace + "#" + function, e.getMessage(), true));
                    throw e;
                } catch (Exception e) {
                    trace.record(new TraceEntry("service-call", namespace + "#" + function, e.getMessage(), true));
                    throw new RuntimeException("Service function " + namespace + "#" + function + " failed", e);
                }
            });
            bindings.putMember("ctx", ctx);

            Value traceObj = context.eval("js", "({})");
            traceObj.putMember("var", (ProxyExecutable) args -> {
                String name = args[0].asString();
                Object value = args[1].isNull() ? null : args[1].as(Object.class);
                trace.record(new TraceEntry("var", name, String.valueOf(value), false));
                return value;
            });
            traceObj.putMember("branch", (ProxyExecutable) args -> {
                String description = args[0].asString();
                boolean condition = args[1].asBoolean();
                trace.record(new TraceEntry("if-branch", description, condition ? "then" : "else", false));
                return condition;
            });
            bindings.putMember("__trace", traceObj);

            Value fn = context.eval("js", "(function() {\n" + compiledJs + "\n})");
            Value result = fn.execute();
            return result.isNull() ? "" : result.asString();
        } catch (PolyglotException e) {
            trace.record(new TraceEntry("script", "execution error", e.getMessage(), true));
            return "";
        }
    }
}
