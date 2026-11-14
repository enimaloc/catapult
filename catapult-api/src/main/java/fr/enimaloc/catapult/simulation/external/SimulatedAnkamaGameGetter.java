package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.getter.AnkamaGameGetter;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The Ankama presence in the account's {@code ankama.presence} mode: the simulated data's game, or nothing on
 * a failure. A subclass only because the detection chain asks for the concrete getter: the superclass's state is
 * never used.
 */
@Primary
@Component
@ConditionalOnBooleanProperty(name = {"catapult.simulation.enabled", "ankama.enabled"})
public class SimulatedAnkamaGameGetter extends AnkamaGameGetter {

    private final ObjectProvider<AnkamaGameGetter> real;
    private final SimulatedCallRunner runner;
    private final SimulationRegistry registry;

    public SimulatedAnkamaGameGetter(@Qualifier("ankamaGameGetter") ObjectProvider<AnkamaGameGetter> real,
                                        SimulatedCallRunner runner, SimulationRegistry registry) {
        super(null, null, null, null);
        this.real = real;
        this.runner = runner;
        this.registry = registry;
    }

    @Override
    public String name() {
        return "ankama";
    }

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        return PresenceDetection.detect(runner, registry, SimulatedCalls.ANKAMA_PRESENCE,
                GetterConfig.Provider.ANKAMA, GameBinding.SourceType.ANKAMA, user,
                () -> real().getCurrentGame(user));
    }

    private AnkamaGameGetter real() {
        AnkamaGameGetter getter = real.getIfAvailable();
        if (getter == null) {
            throw new IllegalStateException("The real Ankama getter is not available");
        }
        return getter;
    }
}
