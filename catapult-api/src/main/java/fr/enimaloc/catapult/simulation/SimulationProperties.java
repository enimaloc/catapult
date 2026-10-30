package fr.enimaloc.catapult.simulation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** {@code catapult.simulation.*}: the simulation layer's switch and where it keeps its state. */
@Component
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
@ConfigurationProperties("catapult.simulation")
@Getter
@Setter
public class SimulationProperties {

    private boolean enabled;

    /** The JSON file the modes and simulated data are kept in, outside the database. */
    private String stateFile = System.getProperty("user.home") + "/.catapult/simulation.json";
}
