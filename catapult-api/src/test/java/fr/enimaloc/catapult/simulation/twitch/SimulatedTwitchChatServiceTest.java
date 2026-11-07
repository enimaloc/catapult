package fr.enimaloc.catapult.simulation.twitch;

import fr.enimaloc.catapult.service.twitch.EventSubTwitchChatService;
import fr.enimaloc.catapult.service.twitch.IrcTwitchChatService;
import fr.enimaloc.catapult.simulation.CallSetting;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationJournal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SimulatedTwitchChatServiceTest {

    @TempDir
    Path dir;

    SimulationFixture fx;
    EventSubTwitchChatService realChat = mock(EventSubTwitchChatService.class);
    SimulatedTwitchChatService service;

    @BeforeEach
    void setUp() {
        fx = new SimulationFixture(dir);
        service = new SimulatedTwitchChatService(SimulationFixture.providerOf(realChat),
                SimulationFixture.<IrcTwitchChatService>providerOf(null), fx.runner, fx.registry);
    }

    @Test
    void chat_send_ok_is_journaled_not_sent() {
        service.sendMessage(fx.fictitious, "hello chat");

        assertThat(fx.journal.recent(fx.fictitious.getId())).singleElement().satisfies(entry -> {
            assertThat(entry.key()).isEqualTo("twitch.chat.send-message");
            assertThat(entry.request()).contains("hello chat");
            assertThat(entry.mode()).isEqualTo("OK");
        });
        verifyNoInteractions(realChat);
    }

    @Test
    void a_failed_send_is_swallowed_like_the_real_transport() {
        fx.set(fx.fictitious, SimulatedCalls.TWITCH_CHAT_SEND_MESSAGE, CallSetting.error(429));

        service.sendMessage(fx.fictitious, "hello chat");

        assertThat(fx.journal.recent(null)).extracting(SimulationJournal.Entry::outcome).containsExactly("http 429");
        verifyNoInteractions(realChat);
    }

    @Test
    void real_mode_sends_through_the_active_transport() {
        fx.set(fx.real, SimulatedCalls.TWITCH_CHAT_SEND_MESSAGE, CallSetting.real());

        service.sendMessage(fx.real, "hello chat");

        verify(realChat).sendMessage(fx.real, "hello chat");
    }

    @Test
    void lookups_answer_plausible_values_and_nothing_on_failure() {
        assertThat(service.getStreamInfo(fx.fictitious)).isEmpty();
        fx.registry.update(fx.fictitious.getId(), data -> data.withLive(true));
        assertThat(service.getStreamInfo(fx.fictitious)).hasValueSatisfying(info ->
                assertThat(info.category()).isEqualTo("Just Chatting"));
        assertThat(service.getUserProfile(fx.fictitious, "bob")).hasValueSatisfying(profile ->
                assertThat(profile.displayName()).isEqualTo("bob"));

        fx.set(fx.fictitious, SimulatedCalls.TWITCH_CHAT_FOLLOWED_AT, CallSetting.timeout());

        assertThat(service.getFollowedAt(fx.fictitious, "bob")).isEmpty();
        assertThat(service.getFollowedAtById(fx.fictitious, "42")).isEmpty();
        verifyNoInteractions(realChat);
    }
}
