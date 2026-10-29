package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.getter.steam.RealSteamApiClient;
import fr.enimaloc.catapult.getter.steam.SteamApiClient.PlayerSummary;
import fr.enimaloc.catapult.simulation.CallSetting;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulatedData.SimulatedPresence;
import fr.enimaloc.catapult.simulation.twitch.SimulationFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SimulatedSteamApiClientTest {

    private static final String FICTITIOUS_STEAM_ID = "76561190000000001";
    private static final String REAL_STEAM_ID = "76561190000000002";

    @TempDir
    Path dir;

    SimulationFixture fx;
    RealSteamApiClient realSteam = mock(RealSteamApiClient.class);
    SimulatedSteamApiClient client;

    @BeforeEach
    void setUp() {
        fx = new SimulationFixture(dir);
        fx.fictitious.setSteamId(FICTITIOUS_STEAM_ID);
        fx.real.setSteamId(REAL_STEAM_ID);
        when(fx.accounts.findBySteamId(FICTITIOUS_STEAM_ID)).thenReturn(Optional.of(fx.fictitious));
        when(fx.accounts.findBySteamId(REAL_STEAM_ID)).thenReturn(Optional.of(fx.real));
        client = new SimulatedSteamApiClient(SimulationFixture.providerOf(realSteam), fx.runner, fx.registry,
                fx.accounts);
    }

    private void playing(String appId, String name) {
        fx.registry.update(fx.fictitious.getId(), data -> data.withPresence(
                Map.of(GetterConfig.Provider.STEAM, new SimulatedPresence(appId, name))));
    }

    @Test
    void steam_presence_ok_reports_the_simulated_game() {
        playing("1091500", "Cyberpunk 2077");

        Optional<PlayerSummary> summary = client.getPlayerSummary(FICTITIOUS_STEAM_ID, null).join();

        assertThat(summary).get().satisfies(player -> {
            assertThat(player.gameId()).isEqualTo("1091500");
            assertThat(player.gameName()).isEqualTo("Cyberpunk 2077");
        });
        assertThat(fx.journaledKeys()).containsExactly(SimulatedCalls.STEAM_PRESENCE.id());
        verifyNoInteractions(realSteam);
    }

    @Test
    void nothing_played_is_an_empty_summary_but_a_present_profile() {
        assertThat(client.getPlayerSummary(FICTITIOUS_STEAM_ID, null).join()).isEmpty();
        assertThat(client.getPlayerProfile(FICTITIOUS_STEAM_ID, null).join()).get()
                .satisfies(player -> assertThat(player.gameId()).isNull());
        verifyNoInteractions(realSteam);
    }

    @Test
    void steam_presence_real_delegates() {
        fx.set(fx.real, SimulatedCalls.STEAM_PRESENCE, CallSetting.real());
        PlayerSummary playing = new PlayerSummary("570", "Dota 2", "someone", "online");
        when(realSteam.getPlayerSummary(REAL_STEAM_ID, "token"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(playing)));

        assertThat(client.getPlayerSummary(REAL_STEAM_ID, "token").join()).contains(playing);

        verify(realSteam).getPlayerSummary(REAL_STEAM_ID, "token");
        // A real call is journaled as REAL
        assertThat(fx.journal.recent(null)).extracting(e -> e.key() + " " + e.mode())
                .containsExactly("steam.presence REAL");
    }

    @Test
    void private_profile_is_simulated() {
        playing("1091500", "Cyberpunk 2077");
        fx.registry.update(fx.fictitious.getId(), data -> data.withSteamProfilePublic(false));

        assertThat(client.isProfilePublic(FICTITIOUS_STEAM_ID).join()).isFalse();
        assertThat(client.getProfileStatus(FICTITIOUS_STEAM_ID, null).join().profilePublic()).isFalse();
        // a private profile hides the game, as Steam does
        assertThat(client.getPlayerSummary(FICTITIOUS_STEAM_ID, null).join()).isEmpty();
        assertThat(client.getOwnedGameIds(FICTITIOUS_STEAM_ID).join()).isEmpty();
        verifyNoInteractions(realSteam);
    }

    @Test
    void a_simulated_failure_answers_what_the_real_client_answers_on_one() {
        playing("1091500", "Cyberpunk 2077");
        fx.set(fx.fictitious, SimulatedCalls.STEAM_PRESENCE, CallSetting.error(500));
        fx.set(fx.fictitious, SimulatedCalls.STEAM_PROFILE_STATUS, CallSetting.timeout());

        assertThat(client.getPlayerSummary(FICTITIOUS_STEAM_ID, null).join()).isEmpty();
        assertThat(client.getProfileStatus(FICTITIOUS_STEAM_ID, null).join().profilePublic()).isFalse();
        verifyNoInteractions(realSteam);
    }

    @Test
    void a_mixed_batch_only_sends_the_real_accounts() {
        playing("1091500", "Cyberpunk 2077");
        fx.set(fx.real, SimulatedCalls.STEAM_PRESENCE, CallSetting.real());
        PlayerSummary playing = new PlayerSummary("570", "Dota 2", "someone", "online");
        when(realSteam.getPlayerSummaries(List.of(REAL_STEAM_ID)))
                .thenReturn(CompletableFuture.completedFuture(Map.of(REAL_STEAM_ID, Optional.of(playing))));

        Map<String, Optional<PlayerSummary>> batch =
                client.getPlayerSummaries(List.of(FICTITIOUS_STEAM_ID, REAL_STEAM_ID)).join();

        verify(realSteam).getPlayerSummaries(List.of(REAL_STEAM_ID));
        // one account lookup per id, the simulated answer reuses it
        verify(fx.accounts).findBySteamId(FICTITIOUS_STEAM_ID);
        assertThat(batch.get(REAL_STEAM_ID)).contains(playing);
        assertThat(batch.get(FICTITIOUS_STEAM_ID)).get()
                .satisfies(player -> assertThat(player.gameName()).isEqualTo("Cyberpunk 2077"));
    }

    @Test
    void an_all_simulated_batch_sends_nothing() {
        client.getPlayerSummaries(List.of(FICTITIOUS_STEAM_ID, REAL_STEAM_ID)).join();

        verifyNoInteractions(realSteam);
    }
}
