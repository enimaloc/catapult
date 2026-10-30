package fr.enimaloc.catapult.simulation.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.event.AccountCreatedEvent;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2UserService;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.access.InviteService;
import fr.enimaloc.catapult.service.access.WhitelistService;
import fr.enimaloc.catapult.service.account.AccountService;
import fr.enimaloc.catapult.service.account.AdminMigrationService;
import fr.enimaloc.catapult.simulation.SimulationProperties;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Fictitious accounts are made and removed as members are, through the real creation and deletion paths. */
class FictitiousAccountServiceTest {

    @TempDir
    Path dir;

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final UserSettingsRepository settings = mock(UserSettingsRepository.class);
    private final GetterConfigRepository getters = mock(GetterConfigRepository.class);
    private final AccountService accountService = mock(AccountService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Map<UUID, UserAccount> stored = new HashMap<>();
    private final List<GetterConfig> savedGetters = new ArrayList<>();
    private SimulationRegistry registry;
    private FictitiousAccountService service;

    @BeforeEach
    void setUp() {
        when(accounts.save(any())).thenAnswer(call -> {
            UserAccount account = call.getArgument(0);
            if (account.getId() == null) {
                account.setId(UUID.randomUUID());
            }
            stored.put(account.getId(), account);
            return account;
        });
        when(accounts.findById(any())).thenAnswer(call -> Optional.ofNullable(stored.get(call.<UUID>getArgument(0))));
        when(accounts.findAllById(any())).thenAnswer(call -> StreamSupport
                .stream(call.<Iterable<UUID>>getArgument(0).spliterator(), false)
                .map(stored::get)
                .filter(java.util.Objects::nonNull)
                .toList());
        when(accounts.findAll()).thenAnswer(call -> new ArrayList<>(stored.values()));
        when(accounts.findBySystemAccountTrue()).thenReturn(Optional.empty());
        when(accounts.findBySteamId(any())).thenAnswer(call -> stored.values().stream()
                .filter(account -> call.getArgument(0).equals(account.getSteamId())).findFirst());
        when(accounts.findByTwitchUsername(any())).thenAnswer(call -> stored.values().stream()
                .filter(account -> call.getArgument(0).equals(account.getTwitchUsername())).findFirst());
        when(accounts.findByTwitchId(any())).thenAnswer(call -> stored.values().stream()
                .filter(account -> call.getArgument(0).equals(account.getTwitchId())).findFirst());
        when(getters.save(any())).thenAnswer(call -> {
            GetterConfig config = call.getArgument(0);
            savedGetters.add(config);
            return config;
        });
        when(getters.findByUserAndProvider(any(), any())).thenAnswer(call -> savedGetters.stream()
                .filter(config -> config.getProvider() == call.getArgument(1))
                .findFirst());

        OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
        CatapultOAuth2UserService members = new CatapultOAuth2UserService(accounts, tokens, settings, getters,
                mock(TokenEncryptionService.class), events, mock(RestClient.class), mock(WhitelistService.class),
                mock(AdminMigrationService.class), mock(InviteService.class));
        SimulationProperties properties = new SimulationProperties();
        properties.setStateFile(dir.resolve("simulation.json").toString());
        registry = new SimulationRegistry(properties, accounts, tokens, mock(MinecraftFriendLinkRepository.class),
                mock(MinecraftServiceAccountRepository.class), new MockEnvironment());
        service = new FictitiousAccountService(accounts, members, getters, accountService, registry, events);
    }

    private UserAccount realAccount(String twitchId) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchId(twitchId);
        account.setTwitchUsername("real-" + twitchId);
        stored.put(account.getId(), account);
        return account;
    }

    @Test
    void creates_a_fictitious_member_with_settings() {
        UserAccount channel = realAccount("12345");

        UserAccount account = service.create(FictitiousIdentity.named("alice"), true, List.of(channel.getId()), false);

        assertThat(account.getTwitchId()).startsWith("m").hasSize(9);
        assertThat(account.getTwitchUsername()).isEqualTo("alice");
        assertThat(account.getStatus()).isEqualTo(UserAccount.Status.ACTIVE);
        assertThat(account.isBotEnabled()).isFalse();
        assertThat(account.getSteamId()).matches("7656119790\\d{7}");
        // Below the first individual SteamID64: no real Steam account has it
        assertThat(Long.parseLong(account.getSteamId())).isLessThan(76561197960265728L);
        ArgumentCaptor<UserSettings> userSettings = ArgumentCaptor.forClass(UserSettings.class);
        verify(settings).save(userSettings.capture());
        assertThat(userSettings.getValue().getUser()).isSameAs(account);
        assertThat(savedGetters).filteredOn(GetterConfig::isEnabled)
                .extracting(GetterConfig::getProvider)
                .containsExactly(GetterConfig.Provider.STEAM);
        assertThat(registry.admin(account.getId())).isTrue();
        assertThat(registry.data(account.getId()).moderatedChannelIds()).containsExactly("12345");
        verify(events).publishEvent(any(AccountCreatedEvent.class));
        assertThat(service.list()).contains(account, channel);
    }

    @Test
    void a_member_is_not_admin_and_unknown_moderated_channels_are_refused() {
        UserAccount account = service.create(FictitiousIdentity.named("bob"), false, List.of(), true);

        assertThat(registry.admin(account.getId())).isFalse();
        assertThat(account.isBotEnabled()).isTrue();
        assertThatThrownBy(() -> service.create(FictitiousIdentity.named("carol"), false, List.of(UUID.randomUUID()), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delete_removes_the_account() {
        UserAccount account = service.create(FictitiousIdentity.named("alice"), true, List.of(), true);

        service.delete(account.getId());

        verify(accountService).deleteAccountImmediately(account);
        assertThat(registry.admin(account.getId())).isFalse();
    }

    @Test
    void delete_refuses_a_real_account() {
        UserAccount real = realAccount("12345");

        assertThatThrownBy(() -> service.delete(real.getId())).isInstanceOf(IllegalArgumentException.class);
        verify(accountService, never()).deleteAccountImmediately(any());
    }

    @Test
    void account_finds_any_account_but_the_system_one() {
        UserAccount real = accounts.save(new UserAccount());
        UserAccount system = new UserAccount();
        system.setSystemAccount(true);
        accounts.save(system);

        assertThat(service.account(real.getId())).contains(real);
        assertThat(service.account(system.getId())).isEmpty();
        assertThat(service.account(UUID.randomUUID())).isEmpty();
    }

    @Test
    void a_typed_twitch_id_gets_the_fictitious_prefix_once() {
        assertThat(service.create(new FictitiousIdentity("alice", " 123 ", null, null), false, List.of(), true)
                .getTwitchId()).isEqualTo("m123");
        assertThat(service.create(new FictitiousIdentity("bob", "m456", null, null), false, List.of(), true)
                .getTwitchId()).isEqualTo("m456");
    }

    @Test
    void a_taken_twitch_id_is_refused() {
        service.create(new FictitiousIdentity("alice", "123", null, null), false, List.of(), true);

        assertThatThrownBy(() -> service.create(new FictitiousIdentity("bob", "m123", null, null), false, List.of(),
                true)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("m123");
    }

    @Test
    void a_typed_steam_id_is_kept_and_a_taken_one_refused() {
        UserAccount alice = service.create(new FictitiousIdentity("alice", null, null, "76561198000000001"), false,
                List.of(), true);

        assertThat(alice.getSteamId()).isEqualTo("76561198000000001");
        assertThatThrownBy(() -> service.create(new FictitiousIdentity("bob", null, null, "76561198000000001"),
                false, List.of(), true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_blank_steam_id_gets_a_fictitious_one_and_the_avatar_is_kept() {
        UserAccount alice = service.create(new FictitiousIdentity("alice", null, " https://cdn/a.png ", " "), false,
                List.of(), true);

        assertThat(alice.getSteamId()).startsWith(FictitiousAccountService.STEAM_ID_PREFIX);
        assertThat(alice.getProfileImageUrl()).isEqualTo("https://cdn/a.png");
    }

    @Test
    void update_identity_changes_the_given_fields_only() {
        UserAccount alice = service.create(new FictitiousIdentity("alice", null, "https://cdn/a.png", null), false,
                List.of(), true);
        String steamId = alice.getSteamId();

        service.updateIdentity(alice.getId(), new FictitiousIdentity("alicia", null, "", null));

        assertThat(alice.getTwitchUsername()).isEqualTo("alicia");
        assertThat(alice.getProfileImageUrl()).isNull();
        assertThat(alice.getSteamId()).isEqualTo(steamId);
        service.updateIdentity(alice.getId(), new FictitiousIdentity(null, null, null, "76561198000000002"));
        assertThat(alice.getSteamId()).isEqualTo("76561198000000002");
        assertThat(alice.getTwitchUsername()).isEqualTo("alicia");
    }

    @Test
    void update_identity_refuses_a_real_account_a_twitch_id_and_a_taken_username() {
        UserAccount real = realAccount("12345");
        UserAccount alice = service.create(FictitiousIdentity.named("alice"), false, List.of(), true);
        service.create(FictitiousIdentity.named("bob"), false, List.of(), true);

        assertThatThrownBy(() -> service.updateIdentity(real.getId(), FictitiousIdentity.named("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateIdentity(alice.getId(), new FictitiousIdentity(null, "m9", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateIdentity(alice.getId(), FictitiousIdentity.named("bob")))
                .isInstanceOf(IllegalArgumentException.class);
        // Its own username is not taken
        service.updateIdentity(alice.getId(), FictitiousIdentity.named("alice"));
    }
}
