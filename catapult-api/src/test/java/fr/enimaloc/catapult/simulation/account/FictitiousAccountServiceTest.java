package fr.enimaloc.catapult.simulation.account;

import fr.enimaloc.catapult.domain.account.OAuthToken;
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
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import fr.enimaloc.catapult.simulation.SimulationProperties;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
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
    private final OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
    private final TwitchChatService chat = mock(TwitchChatService.class);
    private final TwitchEventSubService eventSub = mock(TwitchEventSubService.class);
    private final Map<UUID, UserAccount> stored = new HashMap<>();
    private final List<GetterConfig> savedGetters = new ArrayList<>();
    private SimulationRegistry registry;
    private FictitiousAccountService service;

    @BeforeEach
    void setUp() {
        when(accounts.saveAndFlush(any())).thenAnswer(call -> accounts.save(call.getArgument(0)));
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

        CatapultOAuth2UserService members = new CatapultOAuth2UserService(accounts, tokens, settings, getters,
                mock(TokenEncryptionService.class), events, mock(RestClient.class), mock(WhitelistService.class),
                mock(AdminMigrationService.class), mock(InviteService.class));
        SimulationProperties properties = new SimulationProperties();
        properties.setStateFile(dir.resolve("simulation.json").toString());
        registry = new SimulationRegistry(properties, accounts, tokens, mock(MinecraftFriendLinkRepository.class),
                mock(MinecraftServiceAccountRepository.class), new MockEnvironment());
        service = new FictitiousAccountService(accounts, members, getters, accountService, registry, events, tokens,
                provider(TwitchChatService.class, chat), provider(TwitchEventSubService.class, eventSub));
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean(type.getSimpleName(), bean);
        return factory.getBeanProvider(type);
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

        assertThat(account.getTwitchId()).matches("m\\d{8}");
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

    @Test
    void convert_prefixes_the_twitch_id_and_drops_only_the_twitch_token() {
        UserAccount real = realAccount("12345");
        OAuthToken twitch = new OAuthToken();
        when(tokens.findByUserAndProvider(real, OAuthToken.Provider.TWITCH)).thenReturn(Optional.of(twitch));

        service.convert(real.getId());

        assertThat(real.getTwitchId()).isEqualTo("m12345");
        assertThat(service.isConverted(real.getId())).isTrue();
        assertThat(service.fictitious(real.getId())).contains(real);
        verify(tokens).delete(twitch);
        verify(tokens, never()).findByUserAndProvider(any(), org.mockito.ArgumentMatchers.eq(OAuthToken.Provider.XBOX));
    }

    @Test
    void convert_reconnects_through_the_simulation_when_the_bot_is_on() {
        UserAccount real = realAccount("12345");
        real.setBotEnabled(true);
        List<String> idsAtConnect = new ArrayList<>();
        doAnswer(call -> idsAtConnect.add(call.<UserAccount>getArgument(0).getTwitchId())).when(chat).connect(any());
        doAnswer(call -> idsAtConnect.add(call.<UserAccount>getArgument(0).getTwitchId())).when(eventSub).connect(any());

        service.convert(real.getId());

        InOrder order = inOrder(chat, eventSub);
        order.verify(chat).disconnect(real);
        order.verify(eventSub).disconnect(real);
        order.verify(chat).connect(real);
        order.verify(eventSub).connect(real);
        assertThat(idsAtConnect).containsExactly("m12345", "m12345");
    }

    @Test
    void convert_with_the_bot_off_stays_disconnected() {
        UserAccount real = realAccount("12345");
        real.setBotEnabled(false);

        service.convert(real.getId());

        verify(chat).disconnect(real);
        verify(chat, never()).connect(any());
        verify(eventSub, never()).connect(any());
    }

    @Test
    void convert_refuses_a_fictitious_or_the_system_account() {
        UserAccount fictitious = service.create(FictitiousIdentity.named("alice"), false, List.of(), true);
        UserAccount system = realAccount("1");
        system.setSystemAccount(true);

        assertThatThrownBy(() -> service.convert(fictitious.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.convert(system.getId())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revert_restores_the_real_id_and_forgets_the_simulation() {
        UserAccount real = realAccount("12345");
        service.convert(real.getId());
        registry.setAdmin(real.getId(), true);

        service.revert(real.getId());

        assertThat(real.getTwitchId()).isEqualTo("12345");
        assertThat(service.isConverted(real.getId())).isFalse();
        assertThat(registry.admin(real.getId())).isFalse();
        verify(chat, org.mockito.Mockito.times(2)).disconnect(real);
    }

    @Test
    void revert_is_refused_when_a_real_account_has_the_id_again() {
        UserAccount real = realAccount("12345");
        service.convert(real.getId());
        realAccount("12345");

        assertThatThrownBy(() -> service.revert(real.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(real.getTwitchId()).isEqualTo("m12345");
        assertThat(service.isConverted(real.getId())).isTrue();
    }

    @Test
    void revert_refuses_an_account_that_was_not_converted() {
        UserAccount alice = service.create(new FictitiousIdentity("alice", "999", null, null), false, List.of(), true);

        assertThatThrownBy(() -> service.revert(alice.getId())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delete_refuses_a_converted_account() {
        UserAccount real = realAccount("12345");
        service.convert(real.getId());

        assertThatThrownBy(() -> service.delete(real.getId())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Revert");
        verify(accountService, never()).deleteAccountImmediately(any());
    }

    @Test
    void convert_is_refused_when_its_fictitious_twitch_id_is_taken() {
        UserAccount real = realAccount("12826");
        service.create(new FictitiousIdentity("twitch-copy", "12826", null, null), false, List.of(), true);

        assertThatThrownBy(() -> service.convert(real.getId())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("m12826");
        assertThat(real.getTwitchId()).isEqualTo("12826");
        assertThat(service.isConverted(real.getId())).isFalse();
        verify(chat, never()).disconnect(any());
    }

    @Test
    void convert_keeps_the_exact_login_and_steam_id() {
        UserAccount real = realAccount("12345");
        real.setSteamId("76561198000000042");

        service.convert(real.getId());

        assertThat(real.getTwitchUsername()).isEqualTo("real-12345");
        assertThat(real.getSteamId()).isEqualTo("76561198000000042");
        service.revert(real.getId());
        assertThat(real.getTwitchUsername()).isEqualTo("real-12345");
        assertThat(real.getSteamId()).isEqualTo("76561198000000042");
    }

    @Test
    void a_typed_twitch_id_must_be_numeric() {
        assertThatThrownBy(() -> service.create(new FictitiousIdentity("alice", "abc", null, null), false, List.of(),
                true)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("numeric");
        assertThatThrownBy(() -> service.create(new FictitiousIdentity("bob", "m", null, null), false, List.of(),
                true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.create(new FictitiousIdentity("carol", " m123 ", null, null), false, List.of(), true)
                .getTwitchId()).isEqualTo("m123");
    }
}
