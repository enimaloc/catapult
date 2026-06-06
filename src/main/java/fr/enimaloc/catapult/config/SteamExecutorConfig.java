package fr.enimaloc.catapult.config;

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
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
