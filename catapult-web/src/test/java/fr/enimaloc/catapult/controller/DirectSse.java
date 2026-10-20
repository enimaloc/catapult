package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.monitoring.InteractionMetrics;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * {@link SseStreams} writing on the caller's thread, for the tests that read a stream right after sending
 * to it. Needs {@link InteractionMetrics} (and a meter registry) in the context too.
 */
@TestConfiguration
public class DirectSse {

    @Bean
    SseStreams sseStreams(InteractionMetrics metrics) {
        return SseStreams.direct(metrics);
    }
}
