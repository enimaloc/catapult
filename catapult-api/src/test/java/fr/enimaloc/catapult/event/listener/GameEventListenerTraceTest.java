package fr.enimaloc.catapult.event.listener;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.event.GameDetectedEvent;
import fr.enimaloc.catapult.event.NoGameDetectedEvent;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.binding.BindingService;
import fr.enimaloc.catapult.service.binding.CustomBindingActivations;
import fr.enimaloc.catapult.service.binding.RaidRestoreHold;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.category.CategoryChain;
import fr.enimaloc.catapult.service.category.CategoryChain.DetectionGuard;
import fr.enimaloc.catapult.service.category.CategoryOutcome;
import fr.enimaloc.catapult.service.category.CategoryRule;
import fr.enimaloc.catapult.service.category.CategoryTrigger.GameClosed;
import fr.enimaloc.catapult.service.category.CategoryTrigger.GameDetected;
import fr.enimaloc.catapult.service.debug.DebugExchangeRecorder;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry;
import fr.enimaloc.catapult.service.debug.DetectionTrace;
import fr.enimaloc.catapult.service.debug.RawExchange;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the listener adds to the detection trace of a member in debug mode. */
@ExtendWith(MockitoExtension.class)
class GameEventListenerTraceTest {

    @Mock private BindingService bindingService;
    @Mock private StreamStateService streamStateService;
    @Mock private ChannelEventPublisher channelEventPublisher;
    @Mock private CustomBindingActivations activations;
    @Mock private CategoryChain categoryChain;

    private GameEventListener listener;
    private UserAccount user;
    private GameBinding binding;
    private final DetectedGame doom = new DetectedGame("379720", GameBinding.SourceType.STEAM, "DOOM");

    @BeforeEach
    void setUp() {
        listener = new GameEventListener(bindingService, streamStateService, channelEventPublisher,
                new RaidRestoreHold(), activations, categoryChain);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        binding = new GameBinding();
        binding.setId(UUID.randomUUID());
        binding.setTwitchGameId("517");
        binding.setTwitchGameName("DOOM");
        binding.setStatus(GameBinding.Status.AUTO);
    }

    /** Runs {@code r} under an open trace and returns its binding part. */
    private JsonNode tracedBinding(Runnable r) {
        DebugMemberRegistry debugMembers = mock(DebugMemberRegistry.class);
        DebugExchangeRecorder recorder = mock(DebugExchangeRecorder.class);
        when(debugMembers.modeOf(user.getId()))
                .thenReturn(Optional.of(new DebugMemberRegistry.DebugMode(user.getId(), false)));
        try (DetectionTrace.Scope ignored = DetectionTrace.openIfDebugged(debugMembers, recorder, user.getId())) {
            r.run();
        }
        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(user.getId()), eq(Direction.OUT), raw.capture(), eq("detection.cycle"),
                eq(false), eq("detection"));
        return JsonMapper.builder().build()
                .readTree(new String(raw.getValue().requestBody(), StandardCharsets.UTF_8)).path("binding");
    }

    @Test
    void traceGetsTheGuardDecisionAndTheAppliedBinding() {
        when(activations.active(user)).thenReturn(Optional.empty());
        when(categoryChain.detectionGuard(Optional.empty(), false)).thenReturn(DetectionGuard.NO_CUSTOM_BINDING);
        when(bindingService.resolveOrCreate(user, doom)).thenReturn(binding);
        when(categoryChain.apply(eq(user), any(GameDetected.class)))
                .thenReturn(CategoryOutcome.applied(CategoryRule.GAME_BINDING));

        JsonNode traced = tracedBinding(() -> listener.onGameDetected(new GameDetectedEvent(this, user, doom)));

        assertThat(traced.path("guard").asString()).isEqualTo("NO_CUSTOM_BINDING");
        assertThat(traced.path("id").asString()).isEqualTo(binding.getId().toString());
        assertThat(traced.path("categoryId").asString()).isEqualTo("517");
        assertThat(traced.path("category").asString()).isEqualTo("DOOM");
        assertThat(traced.path("status").asString()).isEqualTo("AUTO");
        assertThat(traced.path("rule").asString()).isEqualTo("GAME_BINDING");
        assertThat(traced.path("result").asString()).isEqualTo("APPLIED");
        assertThat(traced.path("reason").asString()).isEqualTo("NONE");
    }

    @Test
    void traceSaysTheCustomBindingKeptTheChannel() {
        CustomBindingActivation activation = mock(CustomBindingActivation.class);
        when(activations.active(user)).thenReturn(Optional.of(activation));
        when(categoryChain.detectionGuard(Optional.of(activation), true))
                .thenReturn(DetectionGuard.KEEPS_CUSTOM_BINDING);
        when(bindingService.resolveOrCreate(user, doom)).thenReturn(binding);

        JsonNode traced = tracedBinding(() ->
                listener.onGameDetected(new GameDetectedEvent(this, user, doom, true)));

        assertThat(traced.path("guard").asString()).isEqualTo("KEEPS_CUSTOM_BINDING");
        assertThat(traced.path("firstSinceStart").asBoolean()).isTrue();
        assertThat(traced.path("id").asString()).isEqualTo(binding.getId().toString());
        assertThat(traced.has("rule")).isFalse();
    }

    @Test
    void traceGetsTheRuleAppliedWhenNoGameIsLeft() {
        when(categoryChain.apply(eq(user), any(GameClosed.class)))
                .thenReturn(CategoryOutcome.skipped(CategoryRule.NO_GAME_FALLBACK, CategoryOutcome.Reason.OFFLINE));

        JsonNode traced = tracedBinding(() -> listener.onNoGameDetected(new NoGameDetectedEvent(this, user)));

        assertThat(traced.path("rule").asString()).isEqualTo("NO_GAME_FALLBACK");
        assertThat(traced.path("result").asString()).isEqualTo("SKIPPED");
        assertThat(traced.path("reason").asString()).isEqualTo("OFFLINE");
    }
}
