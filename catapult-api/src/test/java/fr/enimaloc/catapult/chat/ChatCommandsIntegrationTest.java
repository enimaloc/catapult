package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.TwitchLoginSuccessHandler;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.igdb.AdminCclService;
import fr.enimaloc.catapult.service.twitch.MockTwitchChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chat commands are retired: whatever a viewer types, and whichever reward is redeemed, Catapult
 * no longer answers in chat. Notifications (a plain {@code sendMessage}) keep working.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mock-web")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:catapult_chat_commands_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
    "app.chat.provider=mock"
})
class ChatCommandsIntegrationTest {

    @MockitoBean AdminCclService adminCclService;
    @MockitoBean TwitchLoginSuccessHandler twitchLoginSuccessHandler;

    @Autowired UserAccountRepository userRepo;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired MockTwitchChatService mockChat;
    @Autowired ChatCommandPresetCatalog presets;
    @Autowired GameStateService gameStateService;

    private UserAccount user;

    @BeforeEach
    void setup() {
        mockChat.clearMessages();
        user = persistUser();
    }

    @Test
    void a_command_the_streamer_defined_is_no_longer_answered() {
        presets.instantiate(user, "game", Locale.FRANCE);
        gameStateService.updateState(user,
            new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2"));

        publish("!game");

        assertThat(mockChat.lastMessageFor(user.getId())).isNull();
    }

    @Test
    void static_commands_and_reward_events_are_no_longer_answered() {
        publish("!debug");
        publish("!setgame");
        publish("reward:Hydrate");

        assertThat(mockChat.lastMessageFor(user.getId())).isNull();
    }

    @Test
    void a_notification_still_reaches_the_chat() {
        mockChat.sendMessage(user, "hello");

        assertThat(mockChat.lastMessageFor(user.getId())).isEqualTo("hello");
    }

    private void publish(String command) {
        publisher.publishEvent(new ChatCommandEvent(this, user, command,
            List.of(), ChatCommandEvent.SenderRole.BROADCASTER));
    }

    private UserAccount persistUser() {
        UserAccount u = new UserAccount();
        u.setTwitchId("chat-it-" + UUID.randomUUID());
        u.setTwitchUsername("chat_it_streamer");
        u.setStatus(UserAccount.Status.ACTIVE);
        return userRepo.save(u);
    }
}
