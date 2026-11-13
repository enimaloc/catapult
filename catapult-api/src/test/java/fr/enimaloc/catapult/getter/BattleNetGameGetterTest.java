package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.service.battlenet.BattleNetGateService;
import fr.enimaloc.catapult.service.battlenet.BattleNetSessionManager;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class BattleNetGameGetterTest {

    private final BattleNetSessionManager sessions = mock(BattleNetSessionManager.class);
    private final BattleNetFriendLinkRepository links = mock(BattleNetFriendLinkRepository.class);
    private final BattleNetGateService gate = mock(BattleNetGateService.class);
    private final ObjectProvider<SimulationGate> simulationGate = mock(ObjectProvider.class);
    private final BattleNetGameGetter getter = new BattleNetGameGetter(sessions, links, gate, simulationGate);

    private UserAccount user;
    private BattleNetFriendLink link;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        link = new BattleNetFriendLink();
        link.setUser(user);
        link.setBnetAccountId(42L);
        link.setBattleTag("Streamer#1234");
        link.setStatus(BattleNetFriendLink.Status.ACCEPTED);
        when(gate.isAvailableReadOnly(user)).thenReturn(true);
        when(links.findByUser(user)).thenReturn(Optional.of(link));
        when(sessions.currentGame(42L)).thenReturn(Optional.of("WTCG"));
    }

    @Test
    void acceptedLinkWithGameIsDetected() {
        assertThat(getter.getCurrentGame(user))
                .contains(new DetectedGame("WTCG", GameBinding.SourceType.BATTLENET, "Hearthstone"));
    }

    @Test
    void acceptedLinkWithoutGameIsNothing() {
        when(sessions.currentGame(42L)).thenReturn(Optional.empty());

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void pendingLinkIsNothing() {
        link.setStatus(BattleNetFriendLink.Status.PENDING);

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void noLinkIsNothing() {
        when(links.findByUser(user)).thenReturn(Optional.empty());

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void unknownFourccUsesTheFourccAsName() {
        when(sessions.currentGame(42L)).thenReturn(Optional.of("ZZZZ"));

        assertThat(getter.getCurrentGame(user))
                .contains(new DetectedGame("ZZZZ", GameBinding.SourceType.BATTLENET, "ZZZZ"));
    }

    @Test
    void closedGateIsNothing() {
        when(gate.isAvailableReadOnly(user)).thenReturn(false);

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void simulatedPresenceIsNothing() {
        SimulationGate active = mock(SimulationGate.class);
        when(simulationGate.getIfAvailable()).thenReturn(active);
        when(active.real(user.getId(), SimulatedCalls.BATTLENET_PRESENCE)).thenReturn(false);

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }
}
