package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.getter.BattleNetGameGetter;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.MinecraftPresenceGetter;
import fr.enimaloc.catapult.getter.XboxGameGetter;
import fr.enimaloc.catapult.simulation.CallSetting;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulatedData.SimulatedPresence;
import fr.enimaloc.catapult.simulation.twitch.SimulationFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SimulatedPresenceGettersTest {

    @TempDir
    Path dir;

    SimulationFixture fx;
    XboxGameGetter realXbox = mock(XboxGameGetter.class);
    MinecraftPresenceGetter realMinecraft = mock(MinecraftPresenceGetter.class);
    BattleNetGameGetter realBattleNet = mock(BattleNetGameGetter.class);
    SimulatedXboxGameGetter xbox;
    SimulatedMinecraftPresenceGetter minecraft;
    SimulatedBattleNetGameGetter battleNet;

    @BeforeEach
    void setUp() {
        fx = new SimulationFixture(dir);
        xbox = new SimulatedXboxGameGetter(SimulationFixture.providerOf(realXbox), fx.runner, fx.registry);
        minecraft = new SimulatedMinecraftPresenceGetter(SimulationFixture.providerOf(realMinecraft), fx.runner,
                fx.registry);
        battleNet = new SimulatedBattleNetGameGetter(SimulationFixture.providerOf(realBattleNet), fx.runner,
                fx.registry);
    }

    private void playing(GetterConfig.Provider provider, String sourceId, String name) {
        fx.registry.update(fx.fictitious.getId(), data -> data.withPresence(
                Map.of(provider, new SimulatedPresence(sourceId, name))));
    }

    @Test
    void xbox_presence_ok_returns_the_simulated_game() {
        playing(GetterConfig.Provider.XBOX, "9NBLGGH4R315", "Halo Infinite");

        Optional<DetectedGame> game = xbox.getCurrentGame(fx.fictitious);

        assertThat(game).contains(new DetectedGame("9NBLGGH4R315", GameBinding.SourceType.XBOX, "Halo Infinite"));
        assertThat(xbox.name()).isEqualTo("Xbox");
        assertThat(fx.journaledKeys()).containsExactly(SimulatedCalls.XBOX_PRESENCE.id());
        verifyNoInteractions(realXbox);
    }

    @Test
    void xbox_presence_without_a_simulated_game_is_empty() {
        assertThat(xbox.getCurrentGame(fx.fictitious)).isEmpty();
        verifyNoInteractions(realXbox);
    }

    @Test
    void xbox_presence_error_answers_nothing_like_the_real_getter() {
        playing(GetterConfig.Provider.XBOX, "9NBLGGH4R315", "Halo Infinite");
        fx.set(fx.fictitious, SimulatedCalls.XBOX_PRESENCE, CallSetting.error(401));

        assertThat(xbox.getCurrentGame(fx.fictitious)).isEmpty();
        assertThat(fx.journal.recent(null)).singleElement()
                .satisfies(entry -> assertThat(entry.outcome()).isEqualTo("http 401"));
        verifyNoInteractions(realXbox);
    }

    @Test
    void xbox_presence_real_delegates() {
        when(fx.tokens.findByUserAndProvider(eq(fx.real), eq(OAuthToken.Provider.XBOX)))
                .thenReturn(Optional.of(new OAuthToken()));
        fx.set(fx.real, SimulatedCalls.XBOX_PRESENCE, CallSetting.real());
        DetectedGame detected = new DetectedGame("id", GameBinding.SourceType.XBOX, "Forza");
        when(realXbox.getCurrentGame(fx.real)).thenReturn(Optional.of(detected));

        assertThat(xbox.getCurrentGame(fx.real)).contains(detected);
        // A real call is journaled as REAL
        assertThat(fx.journal.recent(null)).extracting(e -> e.key() + " " + e.mode())
                .containsExactly("xbox.presence REAL");
    }

    @Test
    void minecraft_presence_ok_returns_the_simulated_game_and_the_cycle_goes_to_the_real_getter() {
        playing(GetterConfig.Provider.MINECRAFT, "minecraft", "Minecraft");
        CompletableFuture<Void> prefetched = CompletableFuture.completedFuture(null);
        when(realMinecraft.prefetchBatch()).thenReturn(prefetched);

        assertThat(minecraft.getCurrentGame(fx.fictitious))
                .contains(new DetectedGame("minecraft", GameBinding.SourceType.MINECRAFT, "Minecraft"));
        // the batch itself only goes out for the accounts whose presence is REAL (gated in the real getter)
        assertThat(minecraft.prefetchBatch()).isSameAs(prefetched);
        minecraft.clearCycleCache();
        verify(realMinecraft).clearCycleCache();
    }

    @Test
    void battlenet_presence_ok_returns_the_simulated_game() {
        playing(GetterConfig.Provider.BATTLENET, "wow", "World of Warcraft");

        assertThat(battleNet.getCurrentGame(fx.fictitious))
                .contains(new DetectedGame("wow", GameBinding.SourceType.BATTLENET, "World of Warcraft"));
        verifyNoInteractions(realBattleNet);
    }
}
