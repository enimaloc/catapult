package fr.enimaloc.catapult.ratelimit;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Who is calling: {@link #LIVE} (a streamer waiting, the default) passes while under a route's learned limit;
 * {@link #BULK} (a recalculation, a refetch) keeps a margin under it and waits its turn. Set around the calls with
 * {@link #run}/{@link #call}, read by {@link LearnedRateLimiter} on the calling thread.
 */
public enum RateLimitPriority {
    LIVE, BULK;

    private record Context(RateLimitPriority priority, BooleanSupplier cancelled) {}

    private static final Context DEFAULT = new Context(LIVE, () -> false);
    private static final ThreadLocal<Context> CURRENT = ThreadLocal.withInitial(() -> DEFAULT);

    /** The calling thread's priority. */
    public static RateLimitPriority current() {
        return CURRENT.get().priority();
    }

    /** Whether the calling thread's work was cancelled: a wait for the limit then gives up. */
    public static boolean cancelled() {
        return CURRENT.get().cancelled().getAsBoolean();
    }

    /** Runs {@code body} at this priority; {@code cancelled} tells a wait for the limit to give up. */
    public static <T> T call(RateLimitPriority priority, BooleanSupplier cancelled, Supplier<T> body) {
        Context previous = CURRENT.get();
        CURRENT.set(new Context(priority, cancelled));
        try {
            return body.get();
        } finally {
            CURRENT.set(previous);
        }
    }

    /** As {@link #call}, without a result. */
    public static void run(RateLimitPriority priority, BooleanSupplier cancelled, Runnable body) {
        call(priority, cancelled, () -> {
            body.run();
            return null;
        });
    }
}
