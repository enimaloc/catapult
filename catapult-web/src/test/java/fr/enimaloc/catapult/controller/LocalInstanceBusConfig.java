package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.cluster.InstanceBus;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** A bus for a lone instance, for the slices that load a bean needing one. */
@TestConfiguration
public class LocalInstanceBusConfig {
    @Bean
    InstanceBus instanceBus() {
        return InstanceBus.local();
    }
}
