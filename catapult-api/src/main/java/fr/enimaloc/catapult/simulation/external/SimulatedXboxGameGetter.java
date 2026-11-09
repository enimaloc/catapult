package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.XboxGameGetter;
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
 * The Xbox presence in the account's {@code xbox.presence} mode. A subclass only because the detection chain asks
 * for the concrete getter: the superclass's state is never used, every method goes to the real getter bean
 * ({@code REAL}) or to the simulated data. A failure detects nothing, as the real getter does.
 */
@Primary
@Component
@ConditionalOnBooleanProperty(name = {"catapult.simulation.enabled", "xbox.enabled"})
public class SimulatedXboxGameGetter extends XboxGameGetter {

    private final ObjectProvider<XboxGameGetter> real;
    private final SimulatedCallRunner runner;
    private final SimulationRegistry registry;

    public SimulatedXboxGameGetter(@Qualifier("xboxGameGetter") ObjectProvider<XboxGameGetter> real,
                                   SimulatedCallRunner runner, SimulationRegistry registry) {
        super(null, null, null, null, Optional.empty());
        this.real = real;
        this.runner = runner;
        this.registry = registry;
    }

    @Override
    public String name() {
        return "Xbox";
    }

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        return PresenceDetection.detect(runner, registry, SimulatedCalls.XBOX_PRESENCE, GetterConfig.Provider.XBOX,
                GameBinding.SourceType.XBOX, user, () -> real().getCurrentGame(user));
    }

    private XboxGameGetter real() {
        XboxGameGetter getter = real.getIfAvailable();
        if (getter == null) {
            throw new IllegalStateException("The real Xbox getter is not available");
        }
        return getter;
    }
}
