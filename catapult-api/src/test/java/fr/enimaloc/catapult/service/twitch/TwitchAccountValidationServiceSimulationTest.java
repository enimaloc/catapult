package fr.enimaloc.catapult.service.twitch;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.debug.DebugRecording;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import fr.enimaloc.catapult.simulation.SimulationGates;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The nightly validation under the simulation: no real call unless the app token is REAL, never a fictitious id. */
@SuppressWarnings({"unchecked", "rawtypes"})
class TwitchAccountValidationServiceSimulationTest {

    UserAccountRepository accounts = mock(UserAccountRepository.class);
    TwitchTokenService tokens = mock(TwitchTokenService.class);
    RestClient restClient = mock(RestClient.class);
    RestClient.RequestHeadersUriSpec getSpec = mock(RestClient.RequestHeadersUriSpec.class);
    RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
    SimulationGate gate = mock(SimulationGate.class);
    UserAccount fictitious = account("mock-alice");
    UserAccount real = account("111");

    private TwitchAccountValidationService service(ObjectProvider<SimulationGate> provider) {
        TwitchAccountValidationService service =
                new TwitchAccountValidationService(accounts, tokens, restClient, DebugRecording.OFF, provider);
        ReflectionTestUtils.setField(service, "twitchClientId", "client-id");
        return service;
    }

    private static UserAccount account(String twitchId) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchId(twitchId);
        account.setStatus(UserAccount.Status.ACTIVE);
        return account;
    }

    private void twitchAnswersOnly(String id) {
        when(accounts.findByStatusAndTwitchIdNotNull(UserAccount.Status.ACTIVE)).thenReturn(List.of(fictitious, real));
        when(tokens.getAppAccessToken()).thenReturn("app-token");
        when(restClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(Map.of("data", List.of(Map.of("id", id, "login", "user"))));
    }

    @Test
    void a_simulated_app_token_skips_the_validation() {
        when(gate.real(null, SimulatedCalls.TWITCH_APP_APP_TOKEN)).thenReturn(false);

        service(SimulationGates.of(gate)).validateAccounts();

        verifyNoInteractions(accounts, tokens, restClient);
    }

    @Test
    void a_real_app_token_validates_the_real_accounts_only() {
        when(gate.real(null, SimulatedCalls.TWITCH_APP_APP_TOKEN)).thenReturn(true);
        twitchAnswersOnly("111");

        service(SimulationGates.of(gate)).validateAccounts();

        verify(getSpec).uri("https://api.twitch.tv/helix/users?id=111");
        assertThat(fictitious.getStatus()).isEqualTo(UserAccount.Status.ACTIVE);
        assertThat(real.getStatus()).isEqualTo(UserAccount.Status.ACTIVE);
    }

    @Test
    void without_the_simulation_fictitious_accounts_are_skipped_too() {
        twitchAnswersOnly("111");

        service(SimulationGates.none()).validateAccounts();

        verify(getSpec).uri("https://api.twitch.tv/helix/users?id=111");
        assertThat(fictitious.getStatus()).isEqualTo(UserAccount.Status.ACTIVE);
    }
}
