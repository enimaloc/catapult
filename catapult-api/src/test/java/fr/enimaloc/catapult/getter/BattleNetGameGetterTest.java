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
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount;
import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    // --- Detection trace ---

    private static void assertTrace(JsonNode entry, String reason) {
        assertThat(entry.path("reason").asString()).isEqualTo(reason);
    }

    @Test
    void traceReason_gateClosed() {
        when(gate.isAvailableReadOnly(user)).thenReturn(false);

        assertTrace(TraceProbe.entryAfter(user, () -> getter.getCurrentGame(user)), "gate closed");
    }

    @Test
    void traceReason_blockedBySimulation() {
        SimulationGate active = mock(SimulationGate.class);
        when(simulationGate.getIfAvailable()).thenReturn(active);
        when(active.real(user.getId(), SimulatedCalls.BATTLENET_PRESENCE)).thenReturn(false);

        assertTrace(TraceProbe.entryAfter(user, () -> getter.getCurrentGame(user)), "blocked by the simulation");
    }

    @Test
    void traceReason_noLink() {
        when(links.findByUser(user)).thenReturn(Optional.empty());

        assertTrace(TraceProbe.entryAfter(user, () -> getter.getCurrentGame(user)), "no friend link");
    }

    @Test
    void traceReason_linkPending() {
        link.setStatus(BattleNetFriendLink.Status.PENDING);
        BattleNetServiceAccount bot = new BattleNetServiceAccount();
        bot.setId(UUID.randomUUID());
        link.setServiceAccount(bot);
        when(sessions.sessionState(bot.getId())).thenReturn("READY");

        JsonNode entry = TraceProbe.entryAfter(user, () -> getter.getCurrentGame(user));

        assertTrace(entry, "friend link is PENDING");
        assertThat(entry.at("/details/session").asString()).isEqualTo("READY");
    }

    @Test
    void traceReason_noGameWithoutAnyPresenceReceived() {
        when(sessions.currentGame(42L)).thenReturn(Optional.empty());
        when(sessions.lastPresence(42L)).thenReturn(Optional.empty());

        JsonNode entry = TraceProbe.entryAfter(user, () -> getter.getCurrentGame(user));

        assertTrace(entry, "no game in the presence");
        assertThat(entry.at("/details/lastPresence").asString())
            .isEqualTo("none received since the session opened");
    }

    @Test
    void traceReason_noGameShowsTheLastPresenceReceived() {
        when(sessions.currentGame(42L)).thenReturn(Optional.empty());
        Instant at = Instant.parse("2026-11-20T10:00:00Z");
        when(sessions.lastPresence(42L))
            .thenReturn(Optional.of(new BattleNetSessionManager.PresenceSeen("WTCG", at, UUID.randomUUID())));

        JsonNode entry = TraceProbe.entryAfter(user, () -> getter.getCurrentGame(user));

        assertThat(entry.at("/details/lastPresence/fourcc").asString()).isEqualTo("WTCG");
        assertThat(entry.at("/details/lastPresence/title").asString()).isEqualTo("Hearthstone");
        assertThat(entry.at("/details/lastPresence/at").asString()).isEqualTo("2026-11-20T10:00:00Z");
    }

    @Test
    void traceReason_isNotComputedWhenNothingIsTraced() {
        when(sessions.currentGame(42L)).thenReturn(Optional.empty());

        assertThat(getter.getCurrentGame(user)).isEmpty();
        verify(sessions, never()).lastPresence(anyLong());
    }
}
