package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.minecraft.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.minecraft.MinecraftServiceAccount;
import fr.enimaloc.catapult.repository.minecraft.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.service.debug.DebugRecording;
import fr.enimaloc.catapult.service.minecraft.MinecraftGateService;
import fr.enimaloc.catapult.service.minecraft.MinecraftService;
import fr.enimaloc.catapult.service.minecraft.MinecraftTokenService;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import fr.enimaloc.catapult.simulation.SimulationGates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Minecraft presence batch under the simulation: a service account's presence call (and its token refresh) only
 * goes out while one of its linked members has {@code minecraft.presence} REAL.
 */
class MinecraftPresenceGetterSimulationTest {

    MinecraftService minecraft = mock(MinecraftService.class);
    MinecraftTokenService tokens = mock(MinecraftTokenService.class);
    MinecraftServiceAccountRepository serviceAccounts = mock(MinecraftServiceAccountRepository.class);
    MinecraftFriendLinkRepository links = mock(MinecraftFriendLinkRepository.class);
    SimulationGate gate = mock(SimulationGate.class);
    MinecraftServiceAccount bot = new MinecraftServiceAccount();
    UserAccount member = new UserAccount();

    @BeforeEach
    void setUp() {
        bot.setId(UUID.randomUUID());
        bot.setLabel("CatapultBot1");
        member.setId(UUID.randomUUID());
        MinecraftFriendLink link = new MinecraftFriendLink();
        link.setUser(member);
        link.setServiceAccount(bot);
        link.setStatus(MinecraftFriendLink.Status.ACCEPTED);
        when(serviceAccounts.findByEnabledTrueOrderByFillOrderAsc()).thenReturn(List.of(bot));
        when(links.existsByServiceAccountAndStatus(bot, MinecraftFriendLink.Status.ACCEPTED)).thenReturn(true);
        when(links.findByServiceAccount(bot)).thenReturn(List.of(link));
        when(tokens.getToken(bot)).thenReturn(Optional.of("mc-token"));
        when(minecraft.updatePresence(anyString(), any(MinecraftService.PresenceStatus.class)))
                .thenReturn(new MinecraftService.PresenceList(new MinecraftService.PresenceList.Presence[0]));
    }

    private MinecraftPresenceGetter getter(ObjectProvider<SimulationGate> provider) {
        return new MinecraftPresenceGetter(minecraft, tokens, serviceAccounts, links, mock(MinecraftGateService.class),
                DebugRecording.OFF, provider);
    }

    @Test
    void a_simulated_presence_sends_nothing() throws Exception {
        when(gate.real(member.getId(), SimulatedCalls.MINECRAFT_PRESENCE)).thenReturn(false);

        getter(SimulationGates.of(gate)).prefetchBatch().get(5, TimeUnit.SECONDS);

        verifyNoInteractions(tokens, minecraft);
    }

    @Test
    void a_member_with_a_real_presence_sends_the_batch() throws Exception {
        when(gate.real(member.getId(), SimulatedCalls.MINECRAFT_PRESENCE)).thenReturn(true);

        getter(SimulationGates.of(gate)).prefetchBatch().get(5, TimeUnit.SECONDS);

        verify(minecraft).updatePresence("mc-token", MinecraftService.PresenceStatus.ONLINE);
    }

    @Test
    void without_the_simulation_the_batch_runs_as_before() throws Exception {
        getter(SimulationGates.none()).prefetchBatch().get(5, TimeUnit.SECONDS);

        verify(minecraft).updatePresence("mc-token", MinecraftService.PresenceStatus.ONLINE);
    }
}
