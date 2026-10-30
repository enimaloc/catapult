package fr.enimaloc.catapult.service.minecraft;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.minecraft.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.minecraft.MinecraftServiceAccount;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.service.debug.DebugRecording;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import fr.enimaloc.catapult.simulation.SimulationGates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The friend-list sync under the simulation: a service account's friends (and its token refresh) are only fetched
 * while one of its linked members has {@code minecraft.presence} REAL. Enrolment and unenrolment only reach Minecraft
 * while the member's {@code minecraft.friends} is REAL.
 */
class MinecraftFriendServiceSimulationTest {

    MinecraftService minecraft = mock(MinecraftService.class);
    MinecraftTokenService tokens = mock(MinecraftTokenService.class);
    MinecraftServiceAccountRepository serviceAccounts = mock(MinecraftServiceAccountRepository.class);
    MinecraftFriendLinkRepository links = mock(MinecraftFriendLinkRepository.class);
    SimulationGate gate = mock(SimulationGate.class);
    MinecraftServiceAccount bot = new MinecraftServiceAccount();
    UserAccount member = new UserAccount();
    MinecraftFriendLink link = new MinecraftFriendLink();

    @BeforeEach
    void setUp() {
        bot.setId(UUID.randomUUID());
        bot.setLabel("CatapultBot1");
        member.setId(UUID.randomUUID());
        link.setUser(member);
        link.setServiceAccount(bot);
        link.setMinecraftProfileId("069a79f444e94726a5befca90e38aaf5");
        link.setStatus(MinecraftFriendLink.Status.ACCEPTED);
        when(serviceAccounts.findByEnabledTrueOrderByFillOrderAsc()).thenReturn(List.of(bot));
        when(links.findByServiceAccount(bot)).thenReturn(List.of(link));
        when(tokens.getToken(bot)).thenReturn(Optional.of("mc-token"));
        when(minecraft.getFriends("mc-token")).thenReturn(new MinecraftService.FriendsList(
                new MinecraftService.FriendsList.Friend[0], new MinecraftService.FriendsList.Friend[0],
                new MinecraftService.FriendsList.Friend[0], true));
    }

    private MinecraftFriendService service(ObjectProvider<SimulationGate> provider) {
        return new MinecraftFriendService(minecraft, tokens, serviceAccounts, links,
                mock(MinecraftAccountLimitMarker.class), mock(GetterConfigRepository.class), new ObjectMapper(),
                mock(ChannelEventPublisher.class), DebugRecording.OFF, provider);
    }

    @Test
    void a_simulated_presence_syncs_nothing() {
        when(gate.real(member.getId(), SimulatedCalls.MINECRAFT_PRESENCE)).thenReturn(false);

        service(SimulationGates.of(gate)).syncFriendLinks();

        verifyNoInteractions(tokens, minecraft);
    }

    @Test
    void a_member_with_a_real_presence_syncs_the_account() {
        when(gate.real(member.getId(), SimulatedCalls.MINECRAFT_PRESENCE)).thenReturn(true);

        service(SimulationGates.of(gate)).syncFriendLinks();

        verify(minecraft).getFriends("mc-token");
    }

    @Test
    void a_simulated_enrolment_links_an_accepted_made_up_player_without_any_call() {
        when(gate.real(member.getId(), SimulatedCalls.MINECRAFT_FRIENDS)).thenReturn(false);
        when(links.findByUser(member)).thenReturn(Optional.empty());
        when(links.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MinecraftFriendLink enrolled = service(SimulationGates.of(gate)).enroll(member, "Notch");

        assertThat(enrolled.getStatus()).isEqualTo(MinecraftFriendLink.Status.ACCEPTED);
        assertThat(enrolled.getMinecraftName()).isEqualTo("Notch");
        assertThat(enrolled.getServiceAccount()).isSameAs(bot);
        assertThat(enrolled.getMinecraftProfileId())
                .isEqualTo(UUID.nameUUIDFromBytes("mock-mc:Notch".getBytes(StandardCharsets.UTF_8)).toString())
                .isEqualTo(MinecraftFriendService.simulatedProfileId("Notch"));
        verifyNoInteractions(tokens, minecraft);
    }

    @Test
    void a_simulated_unenrolment_deletes_the_link_without_removing_the_friend() {
        when(gate.real(member.getId(), SimulatedCalls.MINECRAFT_FRIENDS)).thenReturn(false);
        when(links.findByUser(member)).thenReturn(Optional.of(link));

        service(SimulationGates.of(gate)).unenroll(member);

        verify(links).delete(link);
        verifyNoInteractions(tokens, minecraft);
    }

    @Test
    void a_real_enrolment_looks_the_player_up() {
        when(gate.real(member.getId(), SimulatedCalls.MINECRAFT_FRIENDS)).thenReturn(true);
        when(minecraft.lookupProfile("Notch")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(SimulationGates.of(gate)).enroll(member, "Notch"))
                .isInstanceOf(MinecraftFriendService.MinecraftEnrollmentException.class);

        verify(minecraft).lookupProfile("Notch");
    }

    @Test
    void a_real_unenrolment_removes_the_friend() {
        when(gate.real(member.getId(), SimulatedCalls.MINECRAFT_FRIENDS)).thenReturn(true);
        when(links.findByUser(member)).thenReturn(Optional.of(link));

        service(SimulationGates.of(gate)).unenroll(member);

        verify(minecraft).removeFriend("mc-token", null, link.getMinecraftProfileId());
        verify(links).delete(link);
    }

    @Test
    void without_the_simulation_the_sync_runs_as_before() {
        service(SimulationGates.none()).syncFriendLinks();

        verify(minecraft).getFriends("mc-token");
    }
}
