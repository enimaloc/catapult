package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.epic.EpicFriendLink;
import fr.enimaloc.catapult.repository.epic.EpicFriendLinkRepository;
import fr.enimaloc.catapult.service.epic.EpicGame;
import fr.enimaloc.catapult.service.epic.EpicGateService;
import fr.enimaloc.catapult.service.epic.EpicSessionManager;
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
class EpicGameGetterTest {

    private final EpicSessionManager sessions = mock(EpicSessionManager.class);
    private final EpicFriendLinkRepository links = mock(EpicFriendLinkRepository.class);
    private final EpicGateService gate = mock(EpicGateService.class);
    private final ObjectProvider<SimulationGate> simulationGate = mock(ObjectProvider.class);
    private final EpicGameGetter getter = new EpicGameGetter(sessions, links, gate, simulationGate);

    private UserAccount user;
    private EpicFriendLink link;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        link = new EpicFriendLink();
        link.setUser(user);
        link.setEpicAccountId("acc42");
        link.setStatus(EpicFriendLink.Status.ACCEPTED);
        when(gate.isAvailableReadOnly(user)).thenReturn(true);
        when(links.findByUser(user)).thenReturn(Optional.of(link));
        when(sessions.currentGame("acc42")).thenReturn(Optional.of(new EpicGame("d42d7101", "Fall Guys", true)));
    }

    @Test
    void acceptedLinkInFallGuys_detectsEpicD42d7101FallGuys() {
        assertThat(getter.getCurrentGame(user))
                .contains(new DetectedGame("d42d7101", GameBinding.SourceType.EPIC, "Fall Guys"));
        assertThat(getter.name()).isEqualTo("epic");
    }

    @Test
    void pendingRemovedOrMissingLink_detectsNothing() {
        for (EpicFriendLink.Status status : EpicFriendLink.Status.values()) {
            if (status == EpicFriendLink.Status.ACCEPTED) continue;
            link.setStatus(status);
            assertThat(getter.getCurrentGame(user)).as(status.name()).isEmpty();
        }

        link.setStatus(EpicFriendLink.Status.ACCEPTED);
        link.setEpicAccountId(null);
        assertThat(getter.getCurrentGame(user)).as("no epic account id").isEmpty();

        when(links.findByUser(user)).thenReturn(Optional.empty());
        assertThat(getter.getCurrentGame(user)).as("no link").isEmpty();
    }

    @Test
    void closedGate_detectsNothing() {
        when(gate.isAvailableReadOnly(user)).thenReturn(false);

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void noCurrentGame_detectsNothing() {
        when(sessions.currentGame("acc42")).thenReturn(Optional.empty());

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void simulatedPresenceIsNothing() {
        SimulationGate active = mock(SimulationGate.class);
        when(simulationGate.getIfAvailable()).thenReturn(active);
        when(active.real(user.getId(), SimulatedCalls.EPIC_PRESENCE)).thenReturn(false);

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }
}
