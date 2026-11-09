package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.MinecraftPresenceGetter;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The Minecraft presence in the account's {@code minecraft.presence} mode. The per-cycle batch stays the real
 * getter's, which only calls a service account whose members include one with a {@code REAL} presence; the
 * detection reads that batch for such a member, the simulated data otherwise. A failure detects nothing, as the real
 * getter does. A subclass only because the scheduler and the detection chain ask for the concrete getter: the
 * superclass's state is never used.
 */
@Primary
@Component
@ConditionalOnBooleanProperty(name = {"catapult.simulation.enabled", "minecraft.enabled"})
public class SimulatedMinecraftPresenceGetter extends MinecraftPresenceGetter {

    private final ObjectProvider<MinecraftPresenceGetter> real;
    private final SimulatedCallRunner runner;
    private final SimulationRegistry registry;

    public SimulatedMinecraftPresenceGetter(
            @Qualifier("minecraftPresenceGetter") ObjectProvider<MinecraftPresenceGetter> real,
            SimulatedCallRunner runner, SimulationRegistry registry) {
        super(null, null, null, null, null, null, null);
        this.real = real;
        this.runner = runner;
        this.registry = registry;
    }

    @Override
    public String name() {
        return "Minecraft";
    }

    @Override
    public CompletableFuture<Void> prefetchBatch() {
        return real().prefetchBatch();
    }

    @Override
    public void clearCycleCache() {
        real().clearCycleCache();
    }

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        return PresenceDetection.detect(runner, registry, SimulatedCalls.MINECRAFT_PRESENCE,
                GetterConfig.Provider.MINECRAFT, GameBinding.SourceType.MINECRAFT, user,
                () -> real().getCurrentGame(user));
    }

    private MinecraftPresenceGetter real() {
        MinecraftPresenceGetter getter = real.getIfAvailable();
        if (getter == null) {
            throw new IllegalStateException("The real Minecraft presence getter is not available");
        }
        return getter;
    }
}
