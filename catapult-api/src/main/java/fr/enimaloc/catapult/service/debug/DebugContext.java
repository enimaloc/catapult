package fr.enimaloc.catapult.service.debug;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Tracks, per thread, which member the current work is for and whether outgoing exchanges are being captured
 * instead of attributed. Deliberately independent of the debug registry and of Sentry.
 */
public final class DebugContext {

    private static final ThreadLocal<UUID> SUBJECT = new ThreadLocal<>();
    private static final ThreadLocal<List<RawExchange>> CAPTURE = new ThreadLocal<>();
    private static final Function<UUID, AutoCloseable> NO_HOOK = id -> () -> { };
    private static volatile Function<UUID, AutoCloseable> enterHook = NO_HOOK;

    private DebugContext() {
    }

    public record Captured<T>(T result, List<RawExchange> exchanges) {
    }

    /**
     * Installs a hook called when a subject scope is entered; the returned closeable is closed when it exits.
     * Only the outermost scope of a given subject triggers it (a nested runAs for the same subject, or a wrapped
     * task run while that subject is already current, does not). {@code null} restores the no-op default.
     */
    public static void setEnterHook(Function<UUID, AutoCloseable> hook) {
        enterHook = hook == null ? NO_HOOK : hook;
    }

    public static void runAs(UUID userId, Runnable r) {
        callAs(userId, () -> {
            r.run();
            return null;
        });
    }

    public static <T> T callAs(UUID userId, Supplier<T> s) {
        UUID previous = SUBJECT.get();
        AutoCloseable scope = null;
        if (!userId.equals(previous)) {
            scope = openScope(userId);
        }
        SUBJECT.set(userId);
        try {
            return s.get();
        } finally {
            restore(SUBJECT, previous);
            closeQuietly(scope);
        }
    }

    public static Optional<UUID> subject() {
        return Optional.ofNullable(SUBJECT.get());
    }

    public static boolean capturing() {
        return CAPTURE.get() != null;
    }

    /** Adds an exchange to the active capture; returns false when none is active. */
    public static boolean record(RawExchange exchange) {
        List<RawExchange> list = CAPTURE.get();
        if (list == null) {
            return false;
        }
        list.add(exchange);
        return true;
    }

    public static <T> Captured<T> capture(Supplier<T> call) {
        List<RawExchange> previous = CAPTURE.get();
        List<RawExchange> list = new CopyOnWriteArrayList<>();
        CAPTURE.set(list);
        try {
            T result = call.get();
            return new Captured<>(result, List.copyOf(list));
        } finally {
            restore(CAPTURE, previous);
        }
    }

    public static Runnable wrap(Runnable r) {
        Supplier<Object> s = wrap(() -> {
            r.run();
            return null;
        });
        return s::get;
    }

    public static <T> Supplier<T> wrap(Supplier<T> s) {
        UUID subject = SUBJECT.get();
        List<RawExchange> capture = CAPTURE.get();
        return () -> {
            List<RawExchange> previousCapture = CAPTURE.get();
            CAPTURE.set(capture);
            try {
                return subject == null ? runWithoutSubject(s) : callAs(subject, s);
            } finally {
                restore(CAPTURE, previousCapture);
            }
        };
    }

    private static <T> T runWithoutSubject(Supplier<T> s) {
        UUID previous = SUBJECT.get();
        SUBJECT.remove();
        try {
            return s.get();
        } finally {
            restore(SUBJECT, previous);
        }
    }

    private static AutoCloseable openScope(UUID userId) {
        try {
            return enterHook.apply(userId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void closeQuietly(AutoCloseable scope) {
        if (scope == null) {
            return;
        }
        try {
            scope.close();
        } catch (Exception ignored) {
            // the hook must never break the work it observes
        }
    }

    private static <T> void restore(ThreadLocal<T> local, T previous) {
        if (previous == null) {
            local.remove();
        } else {
            local.set(previous);
        }
    }
}
