package fr.enimaloc.catapult.bgs.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;

/** A scheduler driven by {@link #advance}: no thread, no sleep, a virtual clock in milliseconds. */
final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {

    private final class Task implements ScheduledFuture<Object> {
        final Runnable body;
        final long period;
        long due;
        boolean cancelled;
        boolean done;

        Task(Runnable body, long due, long period) {
            this.body = body;
            this.due = due;
            this.period = period;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(due - now, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), o.getDelay(TimeUnit.MILLISECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterrupt) {
            cancelled = true;
            tasks.remove(this);
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done || cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }

    private final List<Task> tasks = new ArrayList<>();
    private long now;

    /** Moves the clock forward, running every task that falls due, in order. */
    void advance(long millis) {
        long target = now + millis;
        while (true) {
            Task next = tasks.stream().filter(t -> t.due <= target).min(Comparator.comparingLong(t -> t.due))
                    .orElse(null);
            if (next == null) {
                break;
            }
            now = Math.max(now, next.due);
            if (next.period > 0) {
                next.due = now + next.period;
            } else {
                tasks.remove(next);
                next.done = true;
            }
            next.body.run();
        }
        now = target;
    }

    int pending() {
        return tasks.size();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        Task t = new Task(command, now + unit.toMillis(delay), 0);
        tasks.add(t);
        return t;
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        Task t = new Task(command, now + unit.toMillis(initialDelay), unit.toMillis(delay));
        tasks.add(t);
        return t;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return scheduleWithFixedDelay(command, initialDelay, period, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void execute(Runnable command) {
        schedule(command, 0, TimeUnit.MILLISECONDS);
    }

    @Override
    public void shutdown() {
        tasks.clear();
    }

    @Override
    public List<Runnable> shutdownNow() {
        tasks.clear();
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
    }
}
