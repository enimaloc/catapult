package fr.enimaloc.catapult.config;

import fr.enimaloc.catapult.service.debug.DebugContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@Profile("!mock")
@ConditionalOnBooleanProperty("steam.enabled")
public class SteamExecutorConfig {

    @Bean(name = "steamExecutor")
    public Executor steamExecutor() {
        return new DebugAwareExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Runs every task through {@link DebugContext#wrap} and, being {@link AutoCloseable}, lets Spring close the
     * delegate at shutdown (which awaits in-flight tasks).
     */
    static final class DebugAwareExecutor implements Executor, AutoCloseable {
        private final ExecutorService delegate;

        DebugAwareExecutor(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable task) {
            delegate.execute(DebugContext.wrap(task));
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
