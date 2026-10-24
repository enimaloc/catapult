package fr.enimaloc.catapult.service.debug;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DebugContextTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @AfterEach
    void resetHook() {
        DebugContext.setEnterHook(null);
    }

    @Test
    void runAs_setsAndRestoresTheSubject() {
        AtomicReference<Optional<UUID>> inside = new AtomicReference<>();
        DebugContext.runAs(a, () -> inside.set(DebugContext.subject()));
        assertThat(inside.get()).contains(a);
        assertThat(DebugContext.subject()).isEmpty();
    }

    @Test
    void subjectIsClearedAfterRunAs() {
        assertThatThrownBy(() -> DebugContext.runAs(a, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(DebugContext.subject()).isEmpty();
        assertThat(DebugContext.callAs(a, () -> 42)).isEqualTo(42);
        assertThat(DebugContext.subject()).isEmpty();
    }

    /** An entity not yet saved has no id: its work still runs, just unattributed (even inside another subject). */
    @Test
    void runAsNull_runsTheWorkWithoutASubject() {
        AtomicReference<Optional<UUID>> inside = new AtomicReference<>();
        DebugContext.runAs(a, () -> DebugContext.runAs(null, () -> inside.set(DebugContext.subject())));
        assertThat(inside.get()).isEmpty();
        assertThat(DebugContext.callAs(null, () -> 42)).isEqualTo(42);
        assertThat(DebugContext.subject()).isEmpty();
    }

    @Test
    void nestedRunAs_restoresTheOuterSubject() {
        AtomicReference<Optional<UUID>> afterInner = new AtomicReference<>();
        DebugContext.runAs(a, () -> {
            DebugContext.runAs(b, () -> assertThat(DebugContext.subject()).contains(b));
            afterInner.set(DebugContext.subject());
        });
        assertThat(afterInner.get()).contains(a);
    }

    @Test
    void wrap_carriesTheSubjectToAnotherThread() throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Optional<UUID>> inTask = new AtomicReference<>();
            Runnable wrapped = DebugContext.callAs(a, () -> DebugContext.wrap(() -> inTask.set(DebugContext.subject())));
            ex.submit(wrapped).get();
            assertThat(inTask.get()).contains(a);
            AtomicReference<Optional<UUID>> after = new AtomicReference<>();
            ex.submit(() -> after.set(DebugContext.subject())).get();
            assertThat(after.get()).isEmpty();
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void wrap_carriesTheCaptureToAnotherThread() throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            RawExchange exchange = new RawExchange(Instant.now(), "GET", URI.create("http://x/"), null, null,
                    200, null, null, null, 1);
            DebugContext.Captured<Boolean> captured = DebugContext.capture(() -> {
                try {
                    java.util.function.Supplier<Boolean> task = DebugContext.wrap(() -> {
                        DebugContext.record(exchange);
                        return DebugContext.capturing();
                    });
                    return ex.submit(task::get).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(captured.result()).isTrue();
            assertThat(captured.exchanges()).containsExactly(exchange);
            assertThat(DebugContext.capturing()).isFalse();
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void enterHook_isCalledWithTheSubjectAndClosedAfter() {
        List<String> events = new ArrayList<>();
        DebugContext.setEnterHook(id -> {
            events.add("enter " + id);
            return () -> events.add("exit " + id);
        });
        DebugContext.runAs(a, () -> events.add("body"));
        assertThat(events).containsExactly("enter " + a, "body", "exit " + a);
    }

    @Test
    void capture_restoresStateOnException() {
        assertThatThrownBy(() -> DebugContext.capture(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(DebugContext.capturing()).isFalse();
    }

    @Test
    void wrap_restoresStateOnException() {
        Runnable wrapped = DebugContext.callAs(a, () -> DebugContext.wrap((Runnable) () -> {
            throw new IllegalStateException("boom");
        }));
        assertThatThrownBy(wrapped::run).isInstanceOf(IllegalStateException.class);
        assertThat(DebugContext.subject()).isEmpty();
        assertThat(DebugContext.capturing()).isFalse();
    }

    @Test
    void wrap_doesNotLeakTheCaptureOntoTheWorkerThread() throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            DebugContext.capture(() -> {
                try {
                    ex.submit(DebugContext.wrap(() -> { })).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return null;
            });
            AtomicBoolean leaked = new AtomicBoolean(true);
            ex.submit(() -> leaked.set(DebugContext.capturing())).get();
            assertThat(leaked).isFalse();
        } finally {
            ex.shutdownNow();
        }
    }
}
