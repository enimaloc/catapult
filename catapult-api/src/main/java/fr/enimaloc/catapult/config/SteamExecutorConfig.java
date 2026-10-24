package fr.enimaloc.catapult.config;

import fr.enimaloc.catapult.service.debug.DebugContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
@Profile("!mock")
@ConditionalOnBooleanProperty("steam.enabled")
public class SteamExecutorConfig {

    @Bean(name = "steamExecutor")
    public Executor steamExecutor() {
        Executor delegate = Executors.newVirtualThreadPerTaskExecutor();
        return task -> delegate.execute(DebugContext.wrap(task));
    }
}
