package fr.enimaloc.catapult.simulation;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulationRegistryTest {

    @TempDir
    Path dir;

    UserAccountRepository accounts = mock(UserAccountRepository.class);
    OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
    MinecraftFriendLinkRepository minecraftLinks = mock(MinecraftFriendLinkRepository.class);
    MinecraftServiceAccountRepository minecraftServiceAccounts = mock(MinecraftServiceAccountRepository.class);
    MockEnvironment env = new MockEnvironment();
    UUID fictitiousId = UUID.randomUUID();
    UUID realId = UUID.randomUUID();
    UserAccount fictitious = account("mock-alice");
    UserAccount real = account("12345");

    @BeforeEach
    void setUp() {
        when(accounts.findById(fictitiousId)).thenReturn(Optional.of(fictitious));
        when(accounts.findById(realId)).thenReturn(Optional.of(real));
        when(tokens.findByUserAndProvider(any(), any())).thenReturn(Optional.empty());
    }

    private SimulationRegistry registry() {
        SimulationProperties properties = new SimulationProperties();
        properties.setStateFile(dir.resolve("simulation.json").toString());
        return new SimulationRegistry(properties, accounts, tokens, minecraftLinks, minecraftServiceAccounts, env);
    }

    private static UserAccount account(String twitchId) {
        UserAccount account = new UserAccount();
        account.setTwitchId(twitchId);
        return account;
    }

    @Test
    void fictitious_account_defaults_to_ok() {
        SimulationRegistry registry = registry();

        assertThat(registry.setting(fictitiousId, SimulatedCalls.TWITCH_HELIX_UPDATE_CHANNEL))
                .isEqualTo(CallSetting.ok());
        assertThat(registry.setting(fictitiousId, SimulatedCalls.STEAM_PRESENCE)).isEqualTo(CallSetting.ok());
    }

    @Test
    void real_account_defaults_resolution_calls_to_real() {
        env.setProperty("app.igdb.client-id", "id");
        env.setProperty("twitch.client-secret", "secret");
        SimulationRegistry registry = registry();

        assertThat(registry.setting(realId, SimulatedCalls.IGDB_SEARCH).mode()).isEqualTo(CallSetting.Mode.REAL);
        assertThat(registry.setting(realId, SimulatedCalls.TWITCH_HELIX_UPDATE_CHANNEL))
                .isEqualTo(CallSetting.ok());
        assertThat(registry.setting(realId, SimulatedCalls.GITLAB_FEEDBACK)).isEqualTo(CallSetting.ok());
    }

    @Test
    void resolution_calls_stay_ok_without_their_keys() {
        assertThat(registry().setting(realId, SimulatedCalls.IGDB_SEARCH)).isEqualTo(CallSetting.ok());
    }

    @Test
    void real_is_refused_for_a_fictitious_account() {
        SimulationRegistry registry = registry();

        assertThat(registry.realAllowed(fictitiousId, SimulatedCalls.TWITCH_HELIX_UPDATE_CHANNEL)).isFalse();
        assertThatThrownBy(() ->
                registry.set(fictitiousId, SimulatedCalls.TWITCH_HELIX_UPDATE_CHANNEL, CallSetting.real()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void real_is_allowed_for_a_real_account_holding_the_token() {
        when(tokens.findByUserAndProvider(eq(real), eq(OAuthToken.Provider.TWITCH)))
                .thenReturn(Optional.of(new OAuthToken()));
        SimulationRegistry registry = registry();

        assertThat(registry.realAllowed(realId, SimulatedCalls.TWITCH_HELIX_UPDATE_CHANNEL)).isTrue();
        assertThat(registry.realAllowed(realId, SimulatedCalls.STEAM_PRESENCE)).isFalse();
        registry.set(realId, SimulatedCalls.TWITCH_HELIX_UPDATE_CHANNEL, CallSetting.real());
        assertThat(registry.setting(realId, SimulatedCalls.TWITCH_HELIX_UPDATE_CHANNEL).mode())
                .isEqualTo(CallSetting.Mode.REAL);
    }

    @Test
    void global_key_ignores_the_account() {
        SimulationRegistry registry = registry();

        registry.set(fictitiousId, SimulatedCalls.IGDB_GAME, CallSetting.error(503));

        assertThat(registry.setting(realId, SimulatedCalls.IGDB_GAME)).isEqualTo(CallSetting.error(503));
        assertThat(registry.setting(null, SimulatedCalls.IGDB_GAME)).isEqualTo(CallSetting.error(503));
    }

    @Test
    void settings_survive_a_reload_from_the_file() {
        SimulationRegistry registry = registry();
        registry.set(fictitiousId, SimulatedCalls.TWITCH_HELIX_SET_CATEGORY, CallSetting.error(429).withLatency(250));
        registry.set(null, SimulatedCalls.DTDD_SEARCH, CallSetting.timeout());
        registry.update(fictitiousId, data -> data.withLive(true));

        SimulationRegistry reloaded = registry();

        assertThat(reloaded.setting(fictitiousId, SimulatedCalls.TWITCH_HELIX_SET_CATEGORY))
                .isEqualTo(CallSetting.error(429).withLatency(250));
        assertThat(reloaded.setting(null, SimulatedCalls.DTDD_SEARCH)).isEqualTo(CallSetting.timeout());
        assertThat(reloaded.data(fictitiousId).live()).isTrue();
    }

    @Test
    void an_unreadable_state_file_is_moved_aside_and_the_registry_starts_empty() throws Exception {
        Path file = dir.resolve("simulation.json");
        Files.writeString(file, "{ not json");

        SimulationRegistry registry = registry();

        assertThat(registry.setting(fictitiousId, SimulatedCalls.STEAM_PRESENCE)).isEqualTo(CallSetting.ok());
        assertThat(dir.resolve("simulation.json.corrupt")).hasContent("{ not json");
        assertThat(file).doesNotExist();

        // The next save writes a fresh file and leaves the corrupt one alone
        registry.set(fictitiousId, SimulatedCalls.STEAM_PRESENCE, CallSetting.timeout());
        assertThat(file).exists();
        assertThat(dir.resolve("simulation.json.corrupt")).hasContent("{ not json");
    }

    @Test
    void minecraft_friends_is_real_only_for_a_real_account_with_a_service_account() {
        SimulationRegistry registry = registry();
        assertThat(registry.realAllowed(realId, SimulatedCalls.MINECRAFT_FRIENDS)).isFalse();

        when(minecraftServiceAccounts.countByEnabledTrue()).thenReturn(1L);

        assertThat(registry.realAllowed(realId, SimulatedCalls.MINECRAFT_FRIENDS)).isTrue();
        assertThat(registry.realAllowed(fictitiousId, SimulatedCalls.MINECRAFT_FRIENDS)).isFalse();
        // The presence still needs the member's own link
        assertThat(registry.realAllowed(realId, SimulatedCalls.MINECRAFT_PRESENCE)).isFalse();
        assertThat(registry.setting(realId, SimulatedCalls.MINECRAFT_FRIENDS)).isEqualTo(CallSetting.ok());
    }

    @Test
    void data_defaults_are_plausible_and_the_default_survives_a_reload() {
        SimulationRegistry registry = registry();
        registry.update(fictitiousId, data -> data);

        SimulatedData data = registry().data(fictitiousId);

        assertThat(data).isEqualTo(SimulatedData.defaults());
        assertThat(data.installed()).isNotEmpty();
        assertThat(data.active()).isNotNull();
    }

    @Test
    void error_needs_an_http_error_status() {
        SimulationRegistry registry = registry();

        assertThatThrownBy(() -> registry.set(null, SimulatedCalls.IGDB_SEARCH, CallSetting.error(200)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.set(null, SimulatedCalls.IGDB_SEARCH, CallSetting.error(600)))
                .isInstanceOf(IllegalArgumentException.class);
        registry.set(null, SimulatedCalls.IGDB_SEARCH, CallSetting.error(400));
        registry.set(null, SimulatedCalls.IGDB_SEARCH, CallSetting.error(599));
        assertThat(registry.setting(null, SimulatedCalls.IGDB_SEARCH)).isEqualTo(CallSetting.error(599));
    }

    @Test
    void the_app_token_is_real_by_default_when_the_twitch_keys_exist() {
        assertThat(registry().setting(null, SimulatedCalls.TWITCH_APP_APP_TOKEN)).isEqualTo(CallSetting.ok());

        env.setProperty("twitch.client-id", "id");
        env.setProperty("twitch.client-secret", "secret");

        assertThat(registry().setting(null, SimulatedCalls.TWITCH_APP_APP_TOKEN)).isEqualTo(CallSetting.real());
        // the app token is REAL, so the system bot's refresh is too
        assertThat(registry().setting(null, SimulatedCalls.TWITCH_APP_BOT_REFRESH)).isEqualTo(CallSetting.real());
    }

    @Test
    void real_helix_call_forces_a_real_refresh() {
        when(tokens.findByUserAndProvider(eq(real), eq(OAuthToken.Provider.TWITCH)))
                .thenReturn(Optional.of(new OAuthToken()));
        SimulationRegistry registry = registry();
        registry.set(realId, SimulatedCalls.TWITCH_HELIX_REFRESH_TOKEN, CallSetting.error(401).withLatency(30));

        assertThat(registry.setting(realId, SimulatedCalls.TWITCH_HELIX_REFRESH_TOKEN))
                .isEqualTo(CallSetting.error(401).withLatency(30));

        registry.set(realId, SimulatedCalls.TWITCH_HELIX_SET_CATEGORY, CallSetting.real());

        assertThat(registry.setting(realId, SimulatedCalls.TWITCH_HELIX_REFRESH_TOKEN))
                .isEqualTo(CallSetting.real().withLatency(30));
        assertThat(registry.setting(fictitiousId, SimulatedCalls.TWITCH_HELIX_REFRESH_TOKEN))
                .isEqualTo(CallSetting.ok());

        registry.set(realId, SimulatedCalls.TWITCH_HELIX_SET_CATEGORY, CallSetting.ok());
        registry.set(realId, SimulatedCalls.TWITCH_CHAT_SEND_MESSAGE, CallSetting.real());

        assertThat(registry.setting(realId, SimulatedCalls.TWITCH_HELIX_REFRESH_TOKEN).mode())
                .isEqualTo(CallSetting.Mode.REAL);
    }

    @Test
    void a_real_eventsub_event_forces_a_real_refresh() {
        when(tokens.findByUserAndProvider(eq(real), eq(OAuthToken.Provider.TWITCH)))
                .thenReturn(Optional.of(new OAuthToken()));
        SimulationRegistry registry = registry();
        registry.set(realId, SimulatedCalls.TWITCH_EVENTSUB_CHANNEL_RAID, CallSetting.real());

        // The real subscriptions are created with the account's token
        assertThat(registry.setting(realId, SimulatedCalls.TWITCH_HELIX_REFRESH_TOKEN).mode())
                .isEqualTo(CallSetting.Mode.REAL);
    }

    @Test
    void a_real_chat_call_forces_a_real_system_bot_refresh() {
        env.setProperty("twitch.client-id", "id");
        env.setProperty("twitch.client-secret", "secret");
        when(tokens.findByUserAndProvider(eq(real), eq(OAuthToken.Provider.TWITCH)))
                .thenReturn(Optional.of(new OAuthToken()));
        SimulationRegistry registry = registry();
        registry.set(null, SimulatedCalls.TWITCH_APP_APP_TOKEN, CallSetting.ok());
        registry.set(null, SimulatedCalls.TWITCH_APP_CATEGORY_SEARCH, CallSetting.ok());

        assertThat(registry.setting(null, SimulatedCalls.TWITCH_APP_BOT_REFRESH)).isEqualTo(CallSetting.ok());

        registry.set(realId, SimulatedCalls.TWITCH_CHAT_SEND_MESSAGE, CallSetting.real());

        assertThat(registry.setting(null, SimulatedCalls.TWITCH_APP_BOT_REFRESH)).isEqualTo(CallSetting.real());
    }

    @Test
    void converted_survives_a_reload_from_the_file() {
        registry().setConverted(realId, true);

        assertThat(registry().converted(realId)).isTrue();
        assertThat(registry().converted(fictitiousId)).isFalse();
        registry().setConverted(realId, false);
        assertThat(registry().converted(realId)).isFalse();
    }

    @Test
    void a_state_file_without_converted_accounts_still_loads() throws Exception {
        Path file = dir.resolve("simulation.json");
        Files.writeString(file, "{\"global\":{},\"accounts\":{},\"data\":{},\"admins\":[\"" + fictitiousId + "\"]}");

        SimulationRegistry registry = registry();

        assertThat(registry.admin(fictitiousId)).isTrue();
        assertThat(registry.converted(fictitiousId)).isFalse();
        assertThat(dir.resolve("simulation.json.corrupt")).doesNotExist();
    }
}
