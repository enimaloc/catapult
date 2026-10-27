package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.common.dto.notification.NotificationChannel;
import fr.enimaloc.catapult.domain.chatcommand.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentAssignment;
import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.repository.chatcommand.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentRepository;
import fr.enimaloc.catapult.service.chatcommand.ChatCommandGate;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import fr.enimaloc.catapult.service.notification.NotificationDeliveryService;
import fr.enimaloc.catapult.service.notification.NotificationService;
import org.awaitility.Awaitility;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.Set;
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
 * The chat commands are retired. The member below is set up so the former system WOULD have
 * answered (experiment on, commands defined, sender is the app owner for {@code !debug}); each
 * test then shows that nothing is said in chat. Notifications still reach the chat.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mock-web")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:catapult_chat_commands_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
    "app.chat.provider=mock"
})
class ChatCommandsIntegrationTest {

    /** {@code app.owner-id} of the mock-web profile. */
    private static final String OWNER_TWITCH_ID = "mock-admin";

    @MockitoBean AdminCclService adminCclService;
    @MockitoBean TwitchLoginSuccessHandler twitchLoginSuccessHandler;
    @MockitoSpyBean CommandRegistry commandRegistry;

    @Autowired UserAccountRepository userRepo;
    @Autowired ExperimentRepository experimentRepository;
    @Autowired ExperimentAssignmentRepository assignmentRepository;
    @Autowired ExperimentService experimentService;
    @Autowired ChatCommandDefinitionRepository definitions;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired MockTwitchChatService mockChat;
    @Autowired ChatCommandPresetCatalog presets;
    @Autowired GameStateService gameStateService;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired NotificationService notifications;
    @Autowired NotificationDeliveryService deliveries;

    private UserAccount user;

    @BeforeEach
    void setup() {
        mockChat.clearMessages();
        user = persistUser();
        forceEnabledVariant(user);
    }

    @Test
    void a_command_the_streamer_defined_is_no_longer_answered() {
        ChatCommandDefinition game = presets.instantiate(user, "game", Locale.FRANCE);
        gameStateService.updateState(user,
            new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2"));

        publish(game.getName(), "streamer-1");

        assertNoReply();
    }

    @Test
    void the_owner_only_debug_command_is_no_longer_answered() {
        publish("!debug", OWNER_TWITCH_ID);

        assertNoReply();
    }

    @Test
    void setgame_and_reward_events_are_no_longer_answered() {
        ChatCommandDefinition setgame = presets.instantiate(user, "setgame", Locale.FRANCE);
        ChatCommandDefinition reward = new ChatCommandDefinition();
        reward.setUser(user);
        reward.setName("reward:Hydrate");
        reward.setTemplate("Drink water");
        reward.setPermission(ChatCommandEvent.SenderRole.VIEWERS);
        reward.setEnabled(true);
        definitions.save(reward);

        publish(setgame.getName(), user.getTwitchId());
        publish(reward.getName(), "viewer-1");

        assertNoReply();
    }

    @Test
    void nothing_dispatches_a_published_command_any_more() {
        publish("!debug", OWNER_TWITCH_ID);

        org.mockito.Mockito.verify(commandRegistry, org.mockito.Mockito.never())
            .dispatch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void a_notification_still_reaches_the_chat_at_stream_start() {
        user.setBotEnabled(true);
        deliveries.replaceChannels(user, Set.of(NotificationChannel.STREAM_START_CHAT));
        notifications.create(new NotificationService.CreateRequest("Hello", "A notice",
            fr.enimaloc.catapult.common.dto.notification.Severity.INFO, null, null, null, user.getId()), user);

        publisher.publishEvent(new StreamOnlineEvent(this, user));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(mockChat.lastMessageFor(user.getId())).startsWith("[Catapult] Hello"));
    }

    private void assertNoReply() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> Thread.sleep(500));
        assertThat(mockChat.lastMessageFor(user.getId())).isNull();
    }

    private void publish(String command, String senderTwitchId) {
        publisher.publishEvent(new ChatCommandEvent(this, user, command, List.of(),
            ChatCommandEvent.SenderRole.BROADCASTER, senderTwitchId));
    }

    private UserAccount persistUser() {
        UserAccount u = new UserAccount();
        u.setTwitchId("chat-it-" + UUID.randomUUID());
        u.setTwitchUsername("chat_it_streamer");
        u.setStatus(UserAccount.Status.ACTIVE);
        return userRepo.save(u);
    }

    /** The experiment ACTIVE and the member on its enabled variant, as the former commands needed. */
    private void forceEnabledVariant(UserAccount target) {
        experimentService.ensureExists(ChatCommandGate.EXPERIMENT_KEY, "control", "enabled");
        transactionTemplate.executeWithoutResult(status -> {
            Experiment experiment = experimentRepository.findByKey(ChatCommandGate.EXPERIMENT_KEY).orElseThrow();
            if (experiment.getStatus() != Experiment.Status.ACTIVE) {
                experiment.setStatus(Experiment.Status.ACTIVE);
                experimentRepository.save(experiment);
            }
            ExperimentVariant enabled = experiment.getVariants().stream()
                .filter(v -> "enabled".equals(v.getKey())).findFirst().orElseThrow();
            ExperimentAssignment assignment = new ExperimentAssignment();
            assignment.setExperiment(experiment);
            assignment.setVariant(enabled);
            assignment.setUser(target);
            assignmentRepository.save(assignment);
        });
    }
}
