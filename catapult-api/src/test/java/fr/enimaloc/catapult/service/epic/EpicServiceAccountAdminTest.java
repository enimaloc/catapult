package fr.enimaloc.catapult.service.epic;

import fr.enimaloc.catapult.common.dto.admin.EpicAccountDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.domain.epic.EpicFriendLink;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.repository.epic.EpicFriendLinkRepository;
import fr.enimaloc.catapult.repository.epic.EpicServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.epic.api.EpicAccountClient;
import fr.enimaloc.catapult.service.epic.api.EpicAccountClient.Tokens;
import fr.enimaloc.catapult.service.epic.api.EpicAuthException;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpicServiceAccountAdminTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final String CODE = "0123456789abcdef0123456789abcdef";
    private static final String BOT_EPIC_ID = "27871f9dd56446c8a1f1d3db1aaa8a7e";
    private static final String PAGE = """
            {"warning":"Do not share this code with any 3rd party service.",\
            "redirectUrl":"https://localhost/launcher/authorized?code=%s",\
            "authorizationCode":"%s","exchangeCode":null,"sid":null}""".formatted(CODE, CODE);

    private final Map<UUID, EpicServiceAccount> store = new HashMap<>();
    private final EpicServiceAccountRepository accounts = mock(EpicServiceAccountRepository.class);
    private final EpicFriendLinkRepository links = mock(EpicFriendLinkRepository.class);
    private final TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    private final EpicAccountClient client = mock(EpicAccountClient.class);
    private final EpicTokenService tokenService = mock(EpicTokenService.class);
    private final EpicSessionManager sessions = mock(EpicSessionManager.class);
    private final GetterConfigRepository getterConfigs = mock(GetterConfigRepository.class);
    private final ChannelEventPublisher publisher = mock(ChannelEventPublisher.class);
    private EpicServiceAccountAdmin admin;

    @BeforeEach
    void setUp() {
        when(accounts.save(any())).thenAnswer(inv -> {
            EpicServiceAccount account = inv.getArgument(0);
            if (account.getId() == null) account.setId(UUID.randomUUID());
            store.put(account.getId(), account);
            return account;
        });
        when(accounts.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.<UUID>getArgument(0))));
        when(accounts.findAll()).thenAnswer(inv -> List.copyOf(store.values()));
        when(accounts.findByEpicAccountId(anyString())).thenAnswer(inv -> store.values().stream()
                .filter(a -> a.getEpicAccountId().equals(inv.getArgument(0))).findFirst());
        when(accounts.count()).thenAnswer(inv -> (long) store.size());
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        when(sessions.sessionState(any())).thenReturn("DISABLED");
        when(client.exchangeCode(CODE)).thenReturn(tokens(BOT_EPIC_ID, "Catapult-1", NOW.plus(Duration.ofDays(365))));
        admin = new EpicServiceAccountAdmin(accounts, links, encryption, client, tokenService, sessions,
                getterConfigs, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Tokens tokens(String accountId, String displayName, Instant refreshExpiresAt) {
        return new Tokens("the-access", NOW.plus(Duration.ofHours(2)), "the-refresh", refreshExpiresAt, accountId,
                displayName);
    }

    @Test
    void codeFrom_acceptsTheJson_aBareCode_andAQuotedCodeWithSpaces() {
        assertThat(EpicServiceAccountAdmin.codeFrom(PAGE)).isEqualTo(CODE);
        assertThat(EpicServiceAccountAdmin.codeFrom(CODE)).isEqualTo(CODE);
        assertThat(EpicServiceAccountAdmin.codeFrom("  \"" + CODE + "\"\n")).isEqualTo(CODE);
        assertThat(EpicServiceAccountAdmin.codeFrom("'" + CODE.toUpperCase() + "' ")).isEqualTo(CODE);
        assertThat(EpicServiceAccountAdmin.codeFrom("{ \"authorizationCode\" : \"" + CODE + "\" }")).isEqualTo(CODE);
    }

    @Test
    void codeFrom_nullCode_saysToLogInFirst_emptyInput_saysNoCode() {
        String loggedOut = "{\"warning\":\"x\",\"redirectUrl\":\"https://localhost/launcher/authorized?code=null\","
                + "\"authorizationCode\":null,\"exchangeCode\":null,\"sid\":null}";
        assertThatThrownBy(() -> EpicServiceAccountAdmin.codeFrom(loggedOut))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("log in with the bot account first, then reload the page");
        for (String nothing : new String[] {null, "", "   ", "not a code", CODE.substring(1), "{}"}) {
            assertThatThrownBy(() -> EpicServiceAccountAdmin.codeFrom(nothing))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("no authorization code found");
        }
    }

    @Test
    void add_createsAReadyBot_installsTheLogin_andStartsItsSession() {
        EpicAccountDto dto = admin.addOrReauth(PAGE, null);

        EpicServiceAccount saved = store.get(dto.id());
        assertThat(saved.getEpicAccountId()).isEqualTo(BOT_EPIC_ID);
        assertThat(saved.getDisplayName()).isEqualTo("Catapult-1");
        assertThat(saved.getRefreshTokenEnc()).isEqualTo("enc:the-refresh");
        assertThat(saved.getAuthState()).isEqualTo(AuthState.READY);
        assertThat(saved.getFillOrder()).isZero();
        assertThat(dto.displayName()).isEqualTo("Catapult-1");
        assertThat(dto.authState()).isEqualTo("READY");
        InOrder order = inOrder(accounts, tokenService, sessions);
        order.verify(accounts).save(any());
        order.verify(tokenService).install(eq(dto.id()), any());
        order.verify(sessions).restart(dto.id());
    }

    @Test
    void usedCode_is400WithTheExpiredMessage_andCreatesNothing() {
        when(client.exchangeCode(CODE))
                .thenThrow(new EpicAuthException(EpicAuthException.Reason.CODE_EXPIRED))
                .thenThrow(new EpicAuthException(EpicAuthException.Reason.REFRESH_REFUSED))
                .thenThrow(new RestClientException("Epic account service answered HTTP 400"));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> admin.addOrReauth(PAGE, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("this code expired or was already used, get a new one");
        }
        assertThat(store).isEmpty();
        verify(tokenService, never()).install(any(), any());
        verify(sessions, never()).restart(any());
    }

    @Test
    void epicUnreachable_is502_andCreatesNothing() {
        when(client.exchangeCode(CODE)).thenThrow(new ResourceAccessException("timeout"));

        assertThatThrownBy(() -> admin.addOrReauth(PAGE, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
        assertThat(store).isEmpty();
    }

    @Test
    void sameEpicAccountTwice_updatesTheExistingBot() {
        EpicAccountDto first = admin.addOrReauth(PAGE, null);
        store.get(first.id()).setAuthState(AuthState.REAUTH_REQUIRED);
        when(client.exchangeCode(CODE)).thenReturn(tokens(BOT_EPIC_ID, "Catapult-renamed",
                NOW.plus(Duration.ofDays(365))));

        EpicAccountDto second = admin.addOrReauth(CODE, null);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(store).hasSize(1);
        assertThat(store.get(first.id()).getDisplayName()).isEqualTo("Catapult-renamed");
        // the existing bot's refresh token is only written by the token service
        verify(encryption, times(1)).encrypt(anyString());
        verify(tokenService, times(2)).install(eq(first.id()), any());
        verify(sessions, times(2)).restart(first.id());
    }

    @Test
    void reauth_withAnotherAccount_is400() {
        EpicAccountDto bot = admin.addOrReauth(PAGE, null);
        when(client.exchangeCode(CODE)).thenReturn(tokens("ffffffffffffffffffffffffffffffff", "Someone",
                NOW.plus(Duration.ofDays(365))));

        assertThatThrownBy(() -> admin.addOrReauth(PAGE, bot.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("this is not the same Epic account");
        assertThat(store).hasSize(1);
        verify(tokenService, times(1)).install(any(), any());
    }

    @Test
    void reauth_ofAnUnknownBot_is404_beforeTheCodeIsSpent() {
        assertThatThrownBy(() -> admin.addOrReauth(PAGE, UUID.randomUUID()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        verify(client, never()).exchangeCode(anyString());
    }

    @Test
    void aStreamersLinkedAccount_is400_andCreatesNothing() {
        when(links.findByEpicAccountIdAndStatusNot(BOT_EPIC_ID, EpicFriendLink.Status.REMOVED))
                .thenReturn(List.of(new EpicFriendLink()));

        assertThatThrownBy(() -> admin.addOrReauth(PAGE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(store).isEmpty();
        verify(tokenService, never()).install(any(), any());
    }

    @Test
    void dto_neverCarriesTokens_andFlagsAnExpiryUnder30Days() throws Exception {
        EpicAccountDto far = admin.addOrReauth(PAGE, null);
        when(client.exchangeCode(CODE)).thenReturn(tokens("ffffffffffffffffffffffffffffffff", "Catapult-2",
                NOW.plus(Duration.ofDays(29))));
        EpicAccountDto soon = admin.addOrReauth(PAGE, null);

        assertThat(far.refreshExpiresSoon()).isFalse();
        assertThat(soon.refreshExpiresSoon()).isTrue();
        assertThat(soon.refreshExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(29)));
        String json = JsonMapper.builder().build().writeValueAsString(admin.list());
        assertThat(json).doesNotContain("the-access").doesNotContain("the-refresh").doesNotContain(CODE);
    }

    @Test
    void update_editsFillOrderEnabledAndLimit_andRestartsWhenEnabledChanges() {
        EpicAccountDto bot = admin.addOrReauth(PAGE, null);
        store.get(bot.id()).setFriendLimitReached(true);

        EpicAccountDto dto = admin.update(bot.id(), Map.of("fillOrder", 3, "enabled", false,
                "friendLimitReached", false));

        assertThat(dto.fillOrder()).isEqualTo(3);
        assertThat(dto.enabled()).isFalse();
        assertThat(dto.friendLimitReached()).isFalse();
        verify(sessions, times(2)).restart(bot.id()); // the login, then the disable
    }

    @Test
    void update_withAWrongFieldType_is400_andAnUnknownBotIs404() {
        EpicAccountDto bot = admin.addOrReauth(PAGE, null);

        assertThatThrownBy(() -> admin.update(bot.id(), Map.of("enabled", "yes")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> admin.update(UUID.randomUUID(), Map.of("enabled", true)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void delete_stopsTheSession_forgetsTheTokens_dropsTheLinks_disablesTheGetter_andTellsTheOwner() {
        EpicAccountDto bot = admin.addOrReauth(PAGE, null);
        UserAccount owner = new UserAccount();
        owner.setId(UUID.randomUUID());
        EpicFriendLink link = new EpicFriendLink();
        link.setUser(owner);
        link.setServiceAccount(store.get(bot.id()));
        when(links.findByServiceAccountId(bot.id())).thenReturn(List.of(link));
        GetterConfig config = new GetterConfig();
        config.setEnabled(true);
        when(getterConfigs.findByUserAndProvider(owner, GetterConfig.Provider.EPIC)).thenReturn(Optional.of(config));

        admin.delete(bot.id());

        InOrder order = inOrder(sessions, tokenService, accounts);
        order.verify(sessions).stop(bot.id());
        order.verify(tokenService).forget(bot.id());
        order.verify(accounts).delete(any());
        verify(links).deleteAll(List.of(link));
        assertThat(config.isEnabled()).isFalse();
        verify(publisher).publishEpicLinkChanged(eq(owner.getId()),
                eq(EpicLinkStateService.of(EpicLinkStateService.NONE)));
    }
}
