package fr.enimaloc.catapult.service.ankama;

import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink;
import fr.enimaloc.catapult.service.ankama.chat.ChatMessages;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A link and the chat events naming the same streamer share a key, before and after its user id is known. */
class AnkamaDebugKeysTest {

    @Test
    void pendingLink_matchesTheInviteByNameTag() {
        AnkamaFriendLink link = new AnkamaFriendLink();
        link.setRequestedName("Streamer");
        link.setRequestedTag("1234");
        ChatMessages.Event invite = new ChatMessages.InviteCreated(new ChatMessages.Invite(
                new ChatMessages.User(1, "Bot", "0001"), new ChatMessages.User(99, "streamer", "1234")));

        assertThat(AnkamaDebugKeys.of(invite)).containsAnyElementsOf(AnkamaDebugKeys.of(link));
    }

    @Test
    void presence_matchesByUserId() {
        AnkamaFriendLink link = new AnkamaFriendLink();
        link.setAnkamaUserId(99L);

        assertThat(AnkamaDebugKeys.of(new ChatMessages.EndpointsUpdated(99, List.of())))
                .containsAnyElementsOf(AnkamaDebugKeys.of(link));
        assertThat(AnkamaDebugKeys.of(new ChatMessages.Unknown(5))).isEmpty();
    }
}
