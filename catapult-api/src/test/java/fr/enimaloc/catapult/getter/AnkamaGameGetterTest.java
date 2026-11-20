package fr.enimaloc.catapult.getter;

import tools.jackson.databind.JsonNode;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.ankama.AnkamaFriendLinkRepository;
import fr.enimaloc.catapult.service.ankama.AnkamaGateService;
import fr.enimaloc.catapult.service.ankama.AnkamaSessionManager;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class AnkamaGameGetterTest {

    private final AnkamaSessionManager sessions = mock(AnkamaSessionManager.class);
    private final AnkamaFriendLinkRepository links = mock(AnkamaFriendLinkRepository.class);
    private final AnkamaGateService gate = mock(AnkamaGateService.class);
    private final ObjectProvider<SimulationGate> simulationGate = mock(ObjectProvider.class);
    private final AnkamaGameGetter getter = new AnkamaGameGetter(sessions, links, gate, simulationGate);

    private UserAccount user;
    private AnkamaFriendLink link;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        link = new AnkamaFriendLink();
        link.setUser(user);
        link.setAnkamaUserId(42L);
        link.setStatus(AnkamaFriendLink.Status.ACCEPTED);
        when(gate.isAvailableReadOnly(user)).thenReturn(true);
        when(links.findByUser(user)).thenReturn(Optional.of(link));
        when(sessions.currentGame(42L)).thenReturn(OptionalInt.of(3));
    }

    @Test
    void acceptedLinkInWakfu_detectsAnkamaSource3Wakfu() {
        assertThat(getter.getCurrentGame(user))
                .contains(new DetectedGame("3", GameBinding.SourceType.ANKAMA, "Wakfu"));
    }

    @Test
    void pendingRemovedOrMissingLink_detectsNothing() {
        for (AnkamaFriendLink.Status status : new AnkamaFriendLink.Status[]{AnkamaFriendLink.Status.PENDING,
                AnkamaFriendLink.Status.REMOVED, AnkamaFriendLink.Status.INVITE_REJECTED}) {
            link.setStatus(status);
            assertThat(getter.getCurrentGame(user)).as(status.name()).isEmpty();
        }

        link.setStatus(AnkamaFriendLink.Status.ACCEPTED);
        link.setAnkamaUserId(null);
        assertThat(getter.getCurrentGame(user)).as("no ankama user id").isEmpty();

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
        when(sessions.currentGame(42L)).thenReturn(OptionalInt.empty());

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void unknownApplicationIdGetsAGenericName() {
        when(sessions.currentGame(42L)).thenReturn(OptionalInt.of(77));

        assertThat(getter.getCurrentGame(user))
                .contains(new DetectedGame("77", GameBinding.SourceType.ANKAMA, "Ankama game 77"));
    }

    @Test
    void simulatedPresenceIsNothing() {
        SimulationGate active = mock(SimulationGate.class);
        when(simulationGate.getIfAvailable()).thenReturn(active);
        when(active.real(user.getId(), SimulatedCalls.ANKAMA_PRESENCE)).thenReturn(false);

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    // --- Detection trace ---

    @Test
    void traceReason_gateClosed() {
        when(gate.isAvailableReadOnly(user)).thenReturn(false);

        assertThat(TraceProbe.entryAfter(user, () -> getter.getCurrentGame(user)).path("reason").asString())
            .isEqualTo("gate closed");
    }

    @Test
    void traceReason_blockedBySimulation() {
        SimulationGate active = mock(SimulationGate.class);
        when(simulationGate.getIfAvailable()).thenReturn(active);
        when(active.real(user.getId(), SimulatedCalls.ANKAMA_PRESENCE)).thenReturn(false);

        assertThat(TraceProbe.entryAfter(user, () -> getter.getCurrentGame(user)).path("reason").asString())
            .isEqualTo("blocked by the simulation");
    }

    @Test
    void traceReason_noLink() {
        when(links.findByUser(user)).thenReturn(Optional.empty());

        assertThat(TraceProbe.entryAfter(user, () -> getter.getCurrentGame(user)).path("reason").asString())
            .isEqualTo("no friend link");
    }

    @Test
    void traceReason_linkPending() {
        link.setStatus(AnkamaFriendLink.Status.PENDING);
        AnkamaServiceAccount bot = new AnkamaServiceAccount();
        bot.setId(UUID.randomUUID());
        link.setServiceAccount(bot);
        when(sessions.sessionState(bot.getId())).thenReturn("BACKOFF");

        JsonNode entry = TraceProbe.entryAfter(user, () -> getter.getCurrentGame(user));

        assertThat(entry.path("reason").asString()).isEqualTo("friend link is PENDING");
        assertThat(entry.at("/details/session").asString()).isEqualTo("BACKOFF");
    }

    @Test
    void traceReason_linkHasNoAccountIdYet() {
        link.setAnkamaUserId(null);

        assertThat(TraceProbe.entryAfter(user, () -> getter.getCurrentGame(user)).path("reason").asString())
            .isEqualTo("friend link has no account id yet");
    }

    @Test
    void traceReason_noGameInThePresence() {
        when(sessions.currentGame(42L)).thenReturn(OptionalInt.empty());

        assertThat(TraceProbe.entryAfter(user, () -> getter.getCurrentGame(user)).path("reason").asString())
            .isEqualTo("no game in the presence");
    }
}
