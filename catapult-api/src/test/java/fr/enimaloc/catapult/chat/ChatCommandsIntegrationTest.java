package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentAssignment;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.ExperimentRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.TwitchLoginSuccessHandler;
import fr.enimaloc.catapult.service.AdminCclService;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.MockTwitchChatService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the chat-commands flow:
 *   1. The streamer instantiates a preset (creates a {@code ChatCommandDefinition}).
 *   2. {@link GameStateService} holds the current game (populated in prod by the scheduler).
 *   3. A {@link ChatCommandEvent} reaches the {@link ChatCommandListener}, which
 *      checks the {@code chat.commands} experiment gate then dispatches.
 *   4. The dynamic command is resolved, its template is rendered, and the result
 *      is sent through {@link MockTwitchChatService} (captured for assertion).
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
    @Autowired ExperimentRepository experimentRepository;
    @Autowired ExperimentAssignmentRepository assignmentRepository;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired MockTwitchChatService mockChat;
    @Autowired ChatCommandPresetCatalog presets;
    @Autowired GameStateService gameStateService;
    @Autowired TransactionTemplate transactionTemplate;

    private UserAccount user;

    @BeforeEach
    void setup() {
        mockChat.clearMessages();
        user = persistUser();
        forceEnabledVariant(user);
    }

    @Test
    void preset_game_responds_with_template_in_chat() {
        // 1. Streamer instantiates the !game preset (FR locale)
        presets.instantiate(user, "game", Locale.FRANCE);

        // 2. Game detected (Steam, Dota 2). With IGDB disabled (mock-web), the
        //    GameContext is hydrated lazily from GameStateService with only the
        //    detected name — no JSONB write.
        gameStateService.updateState(user,
            new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2"));

        // 3. Chat command received from a viewer
        publisher.publishEvent(new ChatCommandEvent(this, user, "!game",
            List.of(), ChatCommandEvent.SenderRole.EVERYONE));

        // 4. The mock chat service captured the rendered response.
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            String response = mockChat.lastMessageFor(user.getId());
            assertThat(response)
                .as("expected chat response containing the detected game name")
                .isNotNull()
                .contains("Dota 2");
        });
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UserAccount persistUser() {
        UserAccount u = new UserAccount();
        u.setTwitchId("chat-it-" + UUID.randomUUID());
        u.setTwitchUsername("chat_it_streamer");
        u.setStatus(UserAccount.Status.ACTIVE);
        return userRepo.save(u);
    }

    /**
     * The chat-commands experiment is auto-seeded as DRAFT by
     * {@link ChatCommandsExperimentSeeder}. To exercise the dynamic-command path
     * we promote it to ACTIVE and persist an assignment to the "enabled" variant
     * directly — this mirrors the admin override mechanism in production while
     * keeping the test deterministic (no randomness, no rollout bucketing).
     */
    private void forceEnabledVariant(UserAccount target) {
        transactionTemplate.executeWithoutResult(status -> {
            Experiment experiment = experimentRepository.findByKey(ChatCommandListener.EXPERIMENT_KEY)
                .orElseThrow(() -> new IllegalStateException(
                    "chat.commands experiment was not seeded — check ChatCommandsExperimentSeeder"));

            if (experiment.getStatus() != Experiment.Status.ACTIVE) {
                experiment.setStatus(Experiment.Status.ACTIVE);
                experimentRepository.save(experiment);
            }

            ExperimentVariant enabled = experiment.getVariants().stream()
                .filter(v -> "enabled".equals(v.getKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "Missing 'enabled' variant on chat.commands experiment"));

            ExperimentAssignment assignment = new ExperimentAssignment();
            assignment.setExperiment(experiment);
            assignment.setVariant(enabled);
            assignment.setUser(target);
            assignmentRepository.save(assignment);
        });
    }
}
