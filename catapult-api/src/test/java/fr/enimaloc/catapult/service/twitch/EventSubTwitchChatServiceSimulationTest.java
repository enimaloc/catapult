package fr.enimaloc.catapult.service.twitch;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.debug.DebugRecording;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import fr.enimaloc.catapult.simulation.SimulationGates;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The chat WebSocket under the simulation: only opened for an account whose chat connection is REAL. */
class EventSubTwitchChatServiceSimulationTest {

    OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
    UserAccountRepository users = mock(UserAccountRepository.class);
    SimulationGate gate = mock(SimulationGate.class);
    UserAccount user = new UserAccount();

    {
        user.setId(UUID.randomUUID());
        user.setTwitchId("111");
        when(tokens.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.empty());
        when(users.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)).thenReturn(List.of(user));
    }

    private EventSubTwitchChatService service(ObjectProvider<SimulationGate> provider) {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        return new EventSubTwitchChatService(tokens, users, mock(TwitchTokenService.class),
                mock(ApplicationEventPublisher.class), mock(TwitchHelixChannelClient.class), RestClient.create(),
                new ObjectMapper(), mock(SystemTwitchAccountService.class), meters,
                new ExternalApiObservations(ObservationRegistry.NOOP, meters), mock(TwitchChatRateLimiter.class),
                DebugRecording.OFF, provider);
    }

    @Test
    void a_simulated_chat_connection_opens_nothing_at_startup() {
        when(gate.real(user.getId(), SimulatedCalls.TWITCH_CHAT_CONNECT)).thenReturn(false);

        service(SimulationGates.of(gate)).init();

        verify(tokens, never()).findByUserAndProvider(user, OAuthToken.Provider.TWITCH);
    }

    @Test
    void a_real_chat_connection_connects() {
        when(gate.real(user.getId(), SimulatedCalls.TWITCH_CHAT_CONNECT)).thenReturn(true);

        service(SimulationGates.of(gate)).connect(user);

        verify(tokens).findByUserAndProvider(user, OAuthToken.Provider.TWITCH);
    }

    @Test
    void without_the_simulation_startup_connects_as_before() {
        service(SimulationGates.none()).init();

        verify(tokens).findByUserAndProvider(user, OAuthToken.Provider.TWITCH);
    }
}
