package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IrcTwitchChatServiceTest {

    @Test
    void extractRoleBroadcaster() {
        var role = IrcTwitchChatService.extractRole(
            "badge-info=subscriber/12;badges=broadcaster/1,subscriber/0;color=#FF0000");
        assertThat(role).isEqualTo(ChatCommandEvent.SenderRole.BROADCASTER);
    }

    @Test
    void extractRoleModerator() {
        var role = IrcTwitchChatService.extractRole(
            "badge-info=;badges=moderator/1;color=#00FF00");
        assertThat(role).isEqualTo(ChatCommandEvent.SenderRole.MODERATOR);
    }

    @Test
    void extractRoleEveryoneWhenSubscriberOnly() {
        var role = IrcTwitchChatService.extractRole(
            "badge-info=subscriber/3;badges=subscriber/3;color=");
        assertThat(role).isEqualTo(ChatCommandEvent.SenderRole.EVERYONE);
    }

    @Test
    void extractRoleEveryoneWhenEmptyTags() {
        var role = IrcTwitchChatService.extractRole("");
        assertThat(role).isEqualTo(ChatCommandEvent.SenderRole.EVERYONE);
    }

    @Test
    void handleLinePongOnPing() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        IrcTwitchChatService service = buildService(publisher);
        UserAccount user = new UserAccount();
        StringWriter sw = new StringWriter();
        PrintWriter writer = new PrintWriter(sw, true);

        service.handleLine(user, writer, "PING :tmi.twitch.tv");

        assertThat(sw.toString().trim()).isEqualTo("PONG :tmi.twitch.tv");
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void handleLinePublishesChatCommandEvent() {
        List<Object> published = new ArrayList<>();
        ApplicationEventPublisher publisher = published::add;
        IrcTwitchChatService service = buildService(publisher);

        UserAccount user = new UserAccount();
        StringWriter sw = new StringWriter();
        PrintWriter writer = new PrintWriter(sw, true);

        String line = "@badges=broadcaster/1;color=#FF0000 :streamer!streamer@streamer.tmi.twitch.tv PRIVMSG #streamer :!game";
        service.handleLine(user, writer, line);

        assertThat(published).hasSize(1);
        ChatCommandEvent event = (ChatCommandEvent) published.get(0);
        assertThat(event.getCommand()).isEqualTo("!game");
        assertThat(event.getArgs()).isEmpty();
        assertThat(event.getSenderRole()).isEqualTo(ChatCommandEvent.SenderRole.BROADCASTER);
    }

    @Test
    void handleLineIgnoresNonCommandMessages() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        IrcTwitchChatService service = buildService(publisher);

        UserAccount user = new UserAccount();
        StringWriter sw = new StringWriter();
        PrintWriter writer = new PrintWriter(sw, true);

        service.handleLine(user, writer,
            "@badges= :viewer!viewer@viewer.tmi.twitch.tv PRIVMSG #streamer :bonjour");

        verify(publisher, never()).publishEvent(any());
    }

    private IrcTwitchChatService buildService(ApplicationEventPublisher publisher) {
        return new IrcTwitchChatService(null, null, null, publisher, null);
    }
}
