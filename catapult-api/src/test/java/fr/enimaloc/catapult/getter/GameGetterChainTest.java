package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.getter.steam.SteamGameGetter;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.service.emergency.EmergencyStopService;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.service.debug.DebugExchangeRecorder;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry;
import fr.enimaloc.catapult.service.debug.DetectionTrace;
import fr.enimaloc.catapult.service.debug.RawExchange;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameGetterChainTest {

    @Mock GetterConfigRepository getterConfigRepository;
    @Mock SteamGameGetter steamGameGetter;
    @Mock XboxGameGetter xboxGameGetter;
    @Mock EmergencyStopService emergencyStops;

    GameGetterChain chain;
    SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        chain = new GameGetterChain(getterConfigRepository, Optional.of(steamGameGetter), Optional.of(xboxGameGetter), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), registry, emergencyStops);
    }

    @Test
    void resolve_records_detection_timer_with_resolved_by_tag() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        GetterConfig steamConfig = new GetterConfig();
        steamConfig.setProvider(GetterConfig.Provider.STEAM);
        steamConfig.setEnabled(true);
        steamConfig.setPriority(1);

        DetectedGame detectedGame = new DetectedGame("123", GameBinding.SourceType.STEAM, "TestGame");

        when(getterConfigRepository.findByUserOrderByPriorityAsc(user)).thenReturn(List.of(steamConfig));
        when(steamGameGetter.getCurrentGame(user)).thenReturn(Optional.of(detectedGame));

        chain.resolve(user);

        assertThat(registry.get("catapult.game.detection.duration")
                .tag("resolved_by", "steam").timer().count()).isEqualTo(1);
    }

    @Test
    void resolve_rethrowsATimeout_ratherThanTryingTheNextGetter() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        GetterConfig steamConfig = new GetterConfig();
        steamConfig.setProvider(GetterConfig.Provider.STEAM);
        steamConfig.setEnabled(true);
        steamConfig.setPriority(1);

        when(getterConfigRepository.findByUserOrderByPriorityAsc(user)).thenReturn(List.of(steamConfig));
        when(steamGameGetter.getCurrentGame(user))
            .thenThrow(new DetectionTimeoutException("Steam", new TimeoutException()));

        assertThatThrownBy(() -> chain.resolve(user)).isInstanceOf(DetectionTimeoutException.class);
        assertThat(registry.get("catapult.game.detection.duration")
                .tag("resolved_by", "timeout").timer().count()).isEqualTo(1);
    }

    private GetterConfig config(GetterConfig.Provider provider, int priority) {
        GetterConfig config = new GetterConfig();
        config.setProvider(provider);
        config.setEnabled(true);
        config.setPriority(priority);
        return config;
    }

    @Test
    void resolve_stoppedProviderFirst_throwsProviderStopped() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(getterConfigRepository.findByUserOrderByPriorityAsc(user))
            .thenReturn(List.of(config(GetterConfig.Provider.XBOX, 1), config(GetterConfig.Provider.STEAM, 2)));
        when(emergencyStops.isStopped(user, GetterConfig.Provider.XBOX)).thenReturn(true);

        assertThatThrownBy(() -> chain.resolve(user))
            .isInstanceOf(ProviderStoppedException.class)
            .isInstanceOf(DetectionTimeoutException.class)
            .hasMessage("XBOX is suspended by an emergency stop")
            .extracting(e -> ((ProviderStoppedException) e).getProvider()).isEqualTo(GetterConfig.Provider.XBOX);
        verifyNoInteractions(xboxGameGetter);
    }

    @Test
    void resolve_higherPriorityFindsGame_beforeStoppedProvider_returnsIt() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        DetectedGame game = new DetectedGame("123", GameBinding.SourceType.STEAM, "TestGame");
        when(getterConfigRepository.findByUserOrderByPriorityAsc(user))
            .thenReturn(List.of(config(GetterConfig.Provider.STEAM, 1), config(GetterConfig.Provider.XBOX, 2)));
        when(emergencyStops.isStopped(user, GetterConfig.Provider.STEAM)).thenReturn(false);
        when(steamGameGetter.getCurrentGame(user)).thenReturn(Optional.of(game));

        assertThat(chain.resolve(user)).contains(game);
        verifyNoInteractions(xboxGameGetter);
    }

    @Test
    void resolve_stoppedProvider_doesNotCallLowerPriorityGetters() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(getterConfigRepository.findByUserOrderByPriorityAsc(user))
            .thenReturn(List.of(config(GetterConfig.Provider.XBOX, 1), config(GetterConfig.Provider.STEAM, 2)));
        when(emergencyStops.isStopped(user, GetterConfig.Provider.XBOX)).thenReturn(true);

        assertThatThrownBy(() -> chain.resolve(user)).isInstanceOf(ProviderStoppedException.class);
        verifyNoInteractions(steamGameGetter);
    }

    // --- Detection trace of a member in debug mode ---

    @Mock BattleNetGameGetter battleNetGameGetter;

    /** Runs {@code chain.resolve(user)} under an open trace and returns the trace's providers. */
    private JsonNode tracedProviders(GameGetterChain traced, UserAccount user) {
        DebugMemberRegistry debugMembers = mock(DebugMemberRegistry.class);
        DebugExchangeRecorder recorder = mock(DebugExchangeRecorder.class);
        when(debugMembers.modeOf(user.getId()))
            .thenReturn(Optional.of(new DebugMemberRegistry.DebugMode(user.getId(), false)));
        try (DetectionTrace.Scope ignored = DetectionTrace.openIfDebugged(debugMembers, recorder, user.getId())) {
            traced.resolve(user);
        } catch (DetectionTimeoutException expected) {
            // the trace still says how the chain got there
        }
        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(user.getId()), eq(Direction.OUT), raw.capture(), eq("detection.cycle"),
            eq(false), eq("detection"));
        return JsonMapper.builder().build()
            .readTree(new String(raw.getValue().requestBody(), StandardCharsets.UTF_8)).path("providers");
    }

    private static List<String> field(JsonNode providers, String name) {
        List<String> values = new java.util.ArrayList<>();
        providers.forEach(p -> values.add(p.path(name).asString(null)));
        return values;
    }

    private GameGetterChain chainWithBattleNet() {
        return new GameGetterChain(getterConfigRepository, Optional.of(steamGameGetter), Optional.of(xboxGameGetter),
            Optional.of(battleNetGameGetter), Optional.empty(), Optional.empty(), Optional.empty(), registry,
            emergencyStops);
    }

    @Test
    void traceListsEveryProviderInPriorityOrder() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        GetterConfig steam = config(GetterConfig.Provider.STEAM, 1);
        steam.setEnabled(false);
        when(getterConfigRepository.findByUserOrderByPriorityAsc(user)).thenReturn(List.of(steam,
            config(GetterConfig.Provider.XBOX, 2), config(GetterConfig.Provider.MINECRAFT, 3),
            config(GetterConfig.Provider.BATTLENET, 4), config(GetterConfig.Provider.EPIC, 5)));
        when(xboxGameGetter.getCurrentGame(user)).thenAnswer(inv -> {
            DetectionTrace.reason("no linked token");
            return Optional.empty();
        });
        when(battleNetGameGetter.getCurrentGame(user))
            .thenReturn(Optional.of(new DetectedGame("Pro", GameBinding.SourceType.BATTLENET, "Overwatch")));

        JsonNode providers = tracedProviders(chainWithBattleNet(), user);

        assertThat(field(providers, "provider")).containsExactly("STEAM", "XBOX", "MINECRAFT", "BATTLENET", "EPIC");
        assertThat(field(providers, "result")).containsExactly("disabled", "none", "unavailable", "found", "not-asked");
        assertThat(providers.get(1).path("reason").asString()).isEqualTo("no linked token");
        assertThat(providers.get(3).at("/details/sourceId").asString()).isEqualTo("Pro");
        assertThat(providers.get(3).at("/details/sourceType").asString()).isEqualTo("BATTLENET");
        assertThat(providers.get(3).at("/details/sourceName").asString()).isEqualTo("Overwatch");
    }

    @Test
    void traceMarksTimeoutAndNotAskedAfterIt() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(getterConfigRepository.findByUserOrderByPriorityAsc(user)).thenReturn(List.of(
            config(GetterConfig.Provider.XBOX, 1), config(GetterConfig.Provider.STEAM, 2),
            config(GetterConfig.Provider.BATTLENET, 3)));
        when(xboxGameGetter.getCurrentGame(user)).thenThrow(new IllegalStateException("connection reset"));
        when(steamGameGetter.getCurrentGame(user))
            .thenThrow(new DetectionTimeoutException("Steam", new TimeoutException()));

        JsonNode providers = tracedProviders(chainWithBattleNet(), user);

        assertThat(field(providers, "result")).containsExactly("error", "timeout", "not-asked");
        assertThat(providers.get(0).path("reason").asString()).isEqualTo("connection reset");
        assertThat(providers.get(1).path("reason").asString()).isEqualTo("Steam did not answer in time");
        verifyNoInteractions(battleNetGameGetter);
    }

    @Test
    void traceMarksAStoppedProvider() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(getterConfigRepository.findByUserOrderByPriorityAsc(user)).thenReturn(List.of(
            config(GetterConfig.Provider.XBOX, 1), config(GetterConfig.Provider.STEAM, 2)));
        when(emergencyStops.isStopped(user, GetterConfig.Provider.XBOX)).thenReturn(true);

        JsonNode providers = tracedProviders(chain, user);

        assertThat(field(providers, "result")).containsExactly("stopped", "not-asked");
        assertThat(providers.get(0).path("reason").asString()).isEqualTo("XBOX is suspended by an emergency stop");
    }

    @Test
    void traceIsNotTouchedWithoutDebug() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(getterConfigRepository.findByUserOrderByPriorityAsc(user))
            .thenReturn(List.of(config(GetterConfig.Provider.STEAM, 1)));
        when(steamGameGetter.getCurrentGame(user)).thenReturn(Optional.empty());

        assertThat(chain.resolve(user)).isEmpty();
        assertThat(DetectionTrace.active()).isFalse();
    }
}
