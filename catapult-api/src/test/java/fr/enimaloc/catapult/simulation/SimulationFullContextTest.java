package fr.enimaloc.catapult.simulation;

import fr.enimaloc.catapult.FullContextContainers;
import fr.enimaloc.catapult.FullContextTestBase;
import fr.enimaloc.catapult.getter.BattleNetGameGetter;
import fr.enimaloc.catapult.getter.GameGetterChain;
import fr.enimaloc.catapult.getter.MinecraftPresenceGetter;
import fr.enimaloc.catapult.getter.XboxGameGetter;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;
import fr.enimaloc.catapult.getter.dtdd.RealDtddApiClient;
import fr.enimaloc.catapult.getter.steam.RealSteamApiClient;
import fr.enimaloc.catapult.getter.steam.SteamApiClient;
import fr.enimaloc.catapult.service.binding.SchedulerService;
import fr.enimaloc.catapult.service.feedback.GitLabClient;
import fr.enimaloc.catapult.service.igdb.IgdbClient;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.steam.SteamStoreServiceImpl;
import fr.enimaloc.catapult.service.twitch.SystemTwitchAccountService;
import fr.enimaloc.catapult.service.twitch.TwitchCategoryService;
import fr.enimaloc.catapult.service.twitch.TwitchCategoryServiceImpl;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.service.twitch.TwitchServiceImpl;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import fr.enimaloc.catapult.service.xbox.XboxStoreServiceImpl;
import fr.enimaloc.catapult.simulation.external.SimulatedBattleNetGameGetter;
import fr.enimaloc.catapult.simulation.external.SimulatedDtddApiClient;
import fr.enimaloc.catapult.simulation.external.SimulatedGitLabClient;
import fr.enimaloc.catapult.simulation.external.SimulatedIgdbClient;
import fr.enimaloc.catapult.simulation.external.SimulatedMinecraftPresenceGetter;
import fr.enimaloc.catapult.simulation.external.SimulatedSteamApiClient;
import fr.enimaloc.catapult.simulation.external.SimulatedSteamStoreService;
import fr.enimaloc.catapult.simulation.external.SimulatedXboxGameGetter;
import fr.enimaloc.catapult.simulation.external.SimulatedXboxStoreService;
import fr.enimaloc.catapult.simulation.twitch.SimulatedSystemTwitchAccountService;
import fr.enimaloc.catapult.simulation.twitch.SimulatedTwitchCategoryService;
import fr.enimaloc.catapult.simulation.twitch.SimulatedTwitchChatService;
import fr.enimaloc.catapult.simulation.twitch.SimulatedTwitchService;
import fr.enimaloc.catapult.simulation.twitch.SimulatedTwitchTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production bean set with the simulation on (and every presence getter and DTDD enabled): the simulated clients
 * and getters win, the real ones stay reachable.
 */
@Import(FullContextContainers.class)
@TestPropertySource(properties = {"catapult.simulation.enabled=true", "xbox.enabled=true", "minecraft.enabled=true",
        "battlenet.enabled=true", "dtdd.enabled=true"})
class SimulationFullContextTest extends FullContextTestBase {

    @TempDir
    static Path stateDir;

    @DynamicPropertySource
    static void stateFile(DynamicPropertyRegistry registry) {
        registry.add("catapult.simulation.state-file", () -> stateDir.resolve("simulation.json").toString());
    }

    @Test
    void the_simulated_twitch_clients_are_primary() {
        assertThat(context.getBean(TwitchService.class)).isInstanceOf(SimulatedTwitchService.class);
        assertThat(context.getBean(TwitchCategoryService.class)).isInstanceOf(SimulatedTwitchCategoryService.class);
        assertThat(context.getBean(TwitchChatService.class)).isInstanceOf(SimulatedTwitchChatService.class);
        assertThat(context.getBean(TwitchTokenService.class)).isInstanceOf(SimulatedTwitchTokenService.class);
        assertThat(context.getBean(SystemTwitchAccountService.class))
                .isInstanceOf(SimulatedSystemTwitchAccountService.class);
    }

    @Test
    void the_simulated_external_clients_are_primary() {
        assertThat(context.getBean(SteamApiClient.class)).isInstanceOf(SimulatedSteamApiClient.class);
        assertThat(context.getBean(SteamStoreService.class)).isInstanceOf(SimulatedSteamStoreService.class);
        assertThat(context.getBean(XboxStoreService.class)).isInstanceOf(SimulatedXboxStoreService.class);
        assertThat(context.getBean(IgdbClient.class)).isInstanceOf(SimulatedIgdbClient.class);
        assertThat(context.getBean(DtddApiClient.class)).isInstanceOf(SimulatedDtddApiClient.class);
        assertThat(context.getBean(GitLabClient.class)).isInstanceOf(SimulatedGitLabClient.class);
        assertThat(context.getBean(XboxGameGetter.class)).isInstanceOf(SimulatedXboxGameGetter.class);
        assertThat(context.getBean(MinecraftPresenceGetter.class)).isInstanceOf(SimulatedMinecraftPresenceGetter.class);
        assertThat(context.getBean(BattleNetGameGetter.class)).isInstanceOf(SimulatedBattleNetGameGetter.class);
    }

    @Test
    void the_detection_chain_and_the_scheduler_use_the_simulated_getters() {
        GameGetterChain chain = context.getBean(GameGetterChain.class);
        assertThat(ReflectionTestUtils.getField(chain, "xboxGameGetter"))
                .isEqualTo(Optional.of(context.getBean(SimulatedXboxGameGetter.class)));
        assertThat(ReflectionTestUtils.getField(chain, "minecraftPresenceGetter"))
                .isEqualTo(Optional.of(context.getBean(SimulatedMinecraftPresenceGetter.class)));
        assertThat(ReflectionTestUtils.getField(context.getBean(SchedulerService.class), "minecraftPresenceGetter"))
                .isEqualTo(Optional.of(context.getBean(SimulatedMinecraftPresenceGetter.class)));
    }

    @Test
    void the_simulated_getters_and_clients_reach_their_real_bean() {
        assertThat(realOf(context.getBean(SimulatedXboxGameGetter.class)))
                .isNotNull().isNotInstanceOf(SimulatedXboxGameGetter.class);
        assertThat(realOf(context.getBean(SimulatedMinecraftPresenceGetter.class)))
                .isNotNull().isNotInstanceOf(SimulatedMinecraftPresenceGetter.class);
        assertThat(realOf(context.getBean(SimulatedIgdbClient.class)))
                .isNotNull().isNotInstanceOf(SimulatedIgdbClient.class);
        assertThat(realOf(context.getBean(SimulatedGitLabClient.class)))
                .isNotNull().isNotInstanceOf(SimulatedGitLabClient.class);
        assertThat(context.getBeanProvider(RealSteamApiClient.class).getIfAvailable()).isNotNull();
        assertThat(context.getBeanProvider(SteamStoreServiceImpl.class).getIfAvailable()).isNotNull();
        assertThat(context.getBeanProvider(XboxStoreServiceImpl.class).getIfAvailable()).isNotNull();
        assertThat(context.getBeanProvider(RealDtddApiClient.class).getIfAvailable()).isNotNull();
    }

    /** What a decorator's {@code real} provider hands it. */
    private static Object realOf(Object decorator) {
        return ((ObjectProvider<?>) ReflectionTestUtils.getField(decorator, "real")).getIfAvailable();
    }

    @Test
    void the_real_clients_stay_reachable_by_their_concrete_type() {
        assertThat(context.getBeanProvider(TwitchCategoryServiceImpl.class).getIfAvailable()).isNotNull();
        assertThat(context.getBeanProvider(TwitchServiceImpl.class).getIfAvailable()).isNotNull();
    }
}
