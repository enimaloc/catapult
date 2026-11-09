package fr.enimaloc.catapult.service.steam;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.event.SteamLinkedEvent;
import fr.enimaloc.catapult.getter.steam.SteamApiClient;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import fr.enimaloc.catapult.simulation.SimulationGates;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The Steam library preload under the simulation: only run while {@code steam.store.library} is REAL. */
class SteamLibraryCacheServiceSimulationTest {

    SteamApiClient steam = mock(SteamApiClient.class);
    IgdbService igdb = mock(IgdbService.class);
    UserAccountRepository accounts = mock(UserAccountRepository.class);
    SimulationGate gate = mock(SimulationGate.class);
    UserAccount user = new UserAccount();

    {
        user.setId(UUID.randomUUID());
        user.setSteamId("1");
        when(accounts.findBySteamIdNotNull()).thenReturn(List.of(user));
        when(steam.getOwnedGameIds("1", null)).thenReturn(CompletableFuture.completedFuture(List.of("620")));
    }

    private SteamLibraryCacheService service(ObjectProvider<SimulationGate> provider) {
        return new SteamLibraryCacheService(steam, igdb, accounts, mock(TokenEncryptionService.class), provider);
    }

    @Test
    void a_simulated_library_preload_does_nothing() {
        when(gate.real(null, SimulatedCalls.STEAM_STORE_LIBRARY)).thenReturn(false);
        SteamLibraryCacheService service = service(SimulationGates.of(gate));

        service.preloadAllUserLibraries();
        service.onSteamLinked(new SteamLinkedEvent(this, user));

        verifyNoInteractions(steam, igdb, accounts);
    }

    @Test
    void a_real_library_preload_runs() {
        when(gate.real(null, SimulatedCalls.STEAM_STORE_LIBRARY)).thenReturn(true);

        service(SimulationGates.of(gate)).preloadAllUserLibraries();

        verify(igdb).prewarmSteamAppIds(List.of("620"));
    }

    @Test
    void without_the_simulation_a_fictitious_account_is_left_alone() {
        UserAccount fictitious = new UserAccount();
        fictitious.setId(UUID.randomUUID());
        fictitious.setTwitchId("mock-alice");
        fictitious.setSteamId("76561197901234567");
        when(accounts.findBySteamIdNotNull()).thenReturn(List.of(user, fictitious));
        SteamLibraryCacheService service = service(SimulationGates.none());

        service.preloadAllUserLibraries();
        service.onSteamLinked(new SteamLinkedEvent(this, fictitious));

        verify(steam).getOwnedGameIds("1", null);
        verify(steam, never()).getOwnedGameIds("76561197901234567", null);
    }

    @Test
    void without_the_simulation_the_preload_runs_as_before() {
        service(SimulationGates.none()).preloadAllUserLibraries();

        verify(igdb).prewarmSteamAppIds(List.of("620"));
        verify(igdb).prewarmCclCache();
    }
}
