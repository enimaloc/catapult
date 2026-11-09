package fr.enimaloc.catapult.config;

import fr.enimaloc.catapult.service.debug.DebugContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SteamExecutorConfigTest {

    @Test
    void tasksCarryTheSubject_andShutdownAwaitsInFlightTasks() throws Exception {
        UUID id = UUID.randomUUID();
        AtomicReference<Optional<UUID>> seen = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        var ctx = new AnnotationConfigApplicationContext();
        org.springframework.test.context.support.TestPropertySourceUtils
                .addInlinedPropertiesToEnvironment(ctx, "steam.enabled=true");
        ctx.register(SteamExecutorConfig.class);
        ctx.refresh();
        Executor ex = ctx.getBean("steamExecutor", Executor.class);
        DebugContext.runAs(id, () -> ex.execute(() -> {
            started.countDown();
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            seen.set(DebugContext.subject());
            finished.set(true);
        }));
        started.await();
        ctx.close();
        assertThat(finished).isTrue();
        assertThat(seen.get()).contains(id);
    }
}
