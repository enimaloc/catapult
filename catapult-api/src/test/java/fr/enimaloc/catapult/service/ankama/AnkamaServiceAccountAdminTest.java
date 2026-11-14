package fr.enimaloc.catapult.service.ankama;

import fr.enimaloc.catapult.common.dto.admin.AnkamaAccountDto;
import fr.enimaloc.catapult.common.dto.admin.AnkamaLoginStartDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount.AuthState;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.repository.ankama.AnkamaFriendLinkRepository;
import fr.enimaloc.catapult.repository.ankama.AnkamaServiceAccountRepository;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaAuthException;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaHaapiClient;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaHaapiClient.Account;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaHaapiClient.Certificate;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaHaapiClient.Tokens;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaShieldException;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnkamaServiceAccountAdminTest {

    private static final String LAUNCHER_URL = "http://127.0.0.1:9001/authorized?code=the-code";

    /** A clock the test moves. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-14T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final Map<UUID, AnkamaServiceAccount> store = new HashMap<>();
    private final AnkamaServiceAccountRepository accounts = mock(AnkamaServiceAccountRepository.class);
    private final AnkamaFriendLinkRepository links = mock(AnkamaFriendLinkRepository.class);
    private final TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    private final AnkamaHaapiClient haapi = mock(AnkamaHaapiClient.class);
    private final AnkamaSessionManager sessions = mock(AnkamaSessionManager.class);
    private final GetterConfigRepository getterConfigs = mock(GetterConfigRepository.class);
    private final ChannelEventPublisher publisher = mock(ChannelEventPublisher.class);
    private final MovableClock clock = new MovableClock();
    private AnkamaServiceAccountAdmin admin;

    @BeforeEach
    void setUp() {
        when(accounts.save(any())).thenAnswer(inv -> {
            AnkamaServiceAccount account = inv.getArgument(0);
            if (account.getId() == null) account.setId(UUID.randomUUID());
            store.put(account.getId(), account);
            return account;
        });
        when(accounts.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.<UUID>getArgument(0))));
        when(accounts.findAll()).thenAnswer(inv -> List.copyOf(store.values()));
        when(accounts.findByAnkamaAccountId(anyLong())).thenAnswer(inv -> store.values().stream()
                .filter(a -> a.getAnkamaAccountId() == inv.<Long>getArgument(0)).findFirst());
        when(accounts.count()).thenAnswer(inv -> (long) store.size());
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        when(encryption.decrypt(anyString())).thenAnswer(inv -> inv.<String>getArgument(0).substring(4));
        when(sessions.sessionState(any())).thenReturn("DISABLED");
        when(haapi.exchangeCode(anyString(), anyString())).thenReturn(new Tokens("the-key", "the-refresh"));
        when(haapi.account("the-key")).thenReturn(new Account(42L, "Bot", "0001", List.of()));
        admin = new AnkamaServiceAccountAdmin(accounts, links, encryption, haapi, sessions, getterConfigs, publisher,
                clock);
    }

    private AnkamaAccountDto login(UUID reauth, String url) {
        AnkamaLoginStartDto start = admin.startLogin(reauth);
        return admin.completeLogin(start.loginId(), url);
    }

    @Test
    void startLogin_givesAnAuthorizeUrl_andCompleteLogin_createsAReadyBot() {
        AnkamaLoginStartDto start = admin.startLogin(null);

        assertThat(start.authorizeUrl()).startsWith("https://auth.ankama.com/login/ankama?code_challenge=");
        AnkamaAccountDto dto = admin.completeLogin(start.loginId(), LAUNCHER_URL);

        assertThat(dto.displayTag()).isEqualTo("Bot#0001");
        assertThat(dto.authState()).isEqualTo("READY");
        AnkamaServiceAccount saved = store.get(dto.id());
        assertThat(saved.getApiKeyEnc()).isEqualTo("enc:the-key");
        assertThat(saved.getRefreshTokenEnc()).isEqualTo("enc:the-refresh");
        assertThat(saved.getDeviceId()).matches("[0-9a-f]{32}");
        assertThat(saved.getHm1()).matches("[0-9a-f]{32}");
        assertThat(saved.getLastRefreshAt()).isEqualTo(clock.instant());
        verify(sessions).restart(dto.id());
    }

    @Test
    void completeLogin_withAnUnknownExpiredOrReusedLoginId_is400() {
        assertThatThrownBy(() -> admin.completeLogin(UUID.randomUUID(), LAUNCHER_URL))
                .isInstanceOf(IllegalArgumentException.class);

        AnkamaLoginStartDto expired = admin.startLogin(null);
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertThatThrownBy(() -> admin.completeLogin(expired.loginId(), LAUNCHER_URL))
                .isInstanceOf(IllegalArgumentException.class);

        AnkamaLoginStartDto used = admin.startLogin(null);
        admin.completeLogin(used.loginId(), LAUNCHER_URL);
        assertThatThrownBy(() -> admin.completeLogin(used.loginId(), LAUNCHER_URL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completeLogin_isSingleUse_evenWhenTheFirstAttemptFails() {
        AnkamaLoginStartDto start = admin.startLogin(null);

        assertThatThrownBy(() -> admin.completeLogin(start.loginId(), "not a launcher url"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.completeLogin(start.loginId(), LAUNCHER_URL))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown or expired");
    }

    @Test
    void completeLogin_withAnErrorRedirect_is400_andCreatesNothing() {
        AnkamaLoginStartDto start = admin.startLogin(null);

        assertThatThrownBy(() -> admin.completeLogin(start.loginId(),
                "http://127.0.0.1:9001/authorized?error=access_denied"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store).isEmpty();
        verify(haapi, never()).exchangeCode(anyString(), anyString());
    }

    @Test
    void completeLogin_whenAnkamaRefusesTheCode_is400_andCreatesNothing() {
        when(haapi.exchangeCode(anyString(), anyString())).thenThrow(new RestClientException("Ankama answered HTTP 400"));

        assertThatThrownBy(() -> login(null, LAUNCHER_URL)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ankama refused the login, start it again");
        assertThat(store).isEmpty();
    }

    @Test
    void completeLogin_whenAnkamaIsUnreachable_is502() {
        when(haapi.exchangeCode(anyString(), anyString()))
                .thenThrow(new ResourceAccessException("I/O error on POST request"));

        assertThatThrownBy(() -> login(null, LAUNCHER_URL)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
        assertThat(store).isEmpty();
    }

    @Test
    void submitShieldCode_whenTheRequestFails_is502WithoutTheCodeOrHm1() {
        shieldAccount();
        AnkamaAccountDto bot = login(null, LAUNCHER_URL);
        String hm1 = store.get(bot.id()).getHm1();
        when(haapi.validateShieldCode(anyString(), anyString(), anyString())).thenThrow(new ResourceAccessException(
                "I/O error on GET request for \"https://haapi/Shield/ValidateCode?code=257146&hm1=" + hm1 + "\""));

        assertThatThrownBy(() -> admin.submitShieldCode(bot.id(), "257146"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(502);
                    assertThat(String.valueOf(e.getReason())).doesNotContain("257146").doesNotContain(hm1);
                    assertThat(e.getMessage()).doesNotContain("257146").doesNotContain(hm1);
                    assertThat(e.getCause()).isNull();
                });
        assertThat(store.get(bot.id()).getAuthState()).isEqualTo(AuthState.CODE_REQUIRED);
    }

    @Test
    void sameAnkamaAccountTwice_updatesTheExistingBot() {
        AnkamaAccountDto first = login(null, LAUNCHER_URL);
        when(haapi.exchangeCode(anyString(), anyString())).thenReturn(new Tokens("new-key", "new-refresh"));
        when(haapi.account("new-key")).thenReturn(new Account(42L, "Renamed", "0001", List.of()));
        String deviceId = store.get(first.id()).getDeviceId();

        AnkamaAccountDto second = login(null, LAUNCHER_URL);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(store).hasSize(1);
        AnkamaServiceAccount saved = store.get(first.id());
        assertThat(saved.getApiKeyEnc()).isEqualTo("enc:new-key");
        assertThat(saved.getNickname()).isEqualTo("Renamed");
        assertThat(saved.getDeviceId()).isEqualTo(deviceId);
    }

    @Test
    void reauth_withAnotherAnkamaAccount_is400() {
        AnkamaAccountDto bot = login(null, LAUNCHER_URL);
        when(haapi.exchangeCode(anyString(), anyString())).thenReturn(new Tokens("other-key", "other-refresh"));
        when(haapi.account("other-key")).thenReturn(new Account(43L, "Other", "0002", List.of()));

        assertThatThrownBy(() -> login(bot.id(), LAUNCHER_URL))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not the same");
        assertThat(store.get(bot.id()).getApiKeyEnc()).isEqualTo("enc:the-key");
    }

    @Test
    void reauth_withTheSameAnkamaAccount_refreshesTheBot() {
        AnkamaAccountDto bot = login(null, LAUNCHER_URL);
        store.get(bot.id()).setAuthState(AuthState.REAUTH_REQUIRED);
        when(haapi.exchangeCode(anyString(), anyString())).thenReturn(new Tokens("new-key", "new-refresh"));
        when(haapi.account("new-key")).thenReturn(new Account(42L, "Bot", "0001", List.of()));

        AnkamaAccountDto again = login(bot.id(), LAUNCHER_URL);

        assertThat(again.id()).isEqualTo(bot.id());
        assertThat(again.authState()).isEqualTo("READY");
        assertThat(store.get(bot.id()).getApiKeyEnc()).isEqualTo("enc:new-key");
    }

    @Test
    void startLogin_forAnUnknownBot_is404() {
        assertThatThrownBy(() -> admin.startLogin(UUID.randomUUID())).isInstanceOf(ResponseStatusException.class);
    }

    private void shieldAccount() {
        when(haapi.account("the-key")).thenReturn(new Account(42L, "Bot", "0001", List.of("SHIELD")));
        when(haapi.sendShieldCode("the-key")).thenReturn("gmail.com");
    }

    @Test
    void shieldAccount_withoutCertificate_endsCodeRequired_andACodeIsSent() {
        shieldAccount();

        AnkamaAccountDto dto = login(null, LAUNCHER_URL);

        assertThat(dto.authState()).isEqualTo("CODE_REQUIRED");
        assertThat(dto.shieldCodeDomain()).isEqualTo("gmail.com");
        verify(haapi).sendShieldCode("the-key");
        verify(sessions, never()).restart(any());
    }

    @Test
    void shieldAccount_whenTheCodeCannotBeSent_staysCodeRequired_withoutADomain() {
        shieldAccount();
        when(haapi.sendShieldCode("the-key")).thenThrow(new AnkamaAuthException("boom"));

        AnkamaAccountDto dto = login(null, LAUNCHER_URL);

        assertThat(dto.authState()).isEqualTo("CODE_REQUIRED");
        assertThat(dto.shieldCodeDomain()).isNull();
    }

    @Test
    void submitShieldCode_storesTheCertificateEncrypted_andStartsTheSession() {
        shieldAccount();
        AnkamaAccountDto bot = login(null, LAUNCHER_URL);
        String hm1 = store.get(bot.id()).getHm1();
        when(haapi.validateShieldCode("the-key", "257146", hm1)).thenReturn(new Certificate(7L, "cert-body"));

        admin.submitShieldCode(bot.id(), " 257146 ");

        AnkamaServiceAccount saved = store.get(bot.id());
        assertThat(saved.getCertificateId()).isEqualTo(7L);
        assertThat(saved.getCertificateEnc()).isEqualTo("enc:cert-body");
        assertThat(saved.getAuthState()).isEqualTo(AuthState.READY);
        verify(sessions).restart(bot.id());
        assertThat(admin.list().get(0).shieldCodeDomain()).isNull();
    }

    @Test
    void wrongShieldCode_is400_andKeepsCodeRequired() {
        shieldAccount();
        AnkamaAccountDto bot = login(null, LAUNCHER_URL);
        when(haapi.validateShieldCode(anyString(), anyString(), anyString()))
                .thenThrow(new AnkamaShieldException("Invalid security code"));

        assertThatThrownBy(() -> admin.submitShieldCode(bot.id(), "000000"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid security code");
        assertThat(store.get(bot.id()).getAuthState()).isEqualTo(AuthState.CODE_REQUIRED);
        assertThat(store.get(bot.id()).getCertificateEnc()).isNull();
        verify(sessions, never()).restart(any());
    }

    @Test
    void submitAndResend_onlyApplyToACodeRequiredBot() {
        AnkamaAccountDto bot = login(null, LAUNCHER_URL);

        assertThatThrownBy(() -> admin.submitShieldCode(bot.id(), "123456"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> admin.resendShieldCode(bot.id())).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void resendShieldCode_sendsANewCode() {
        shieldAccount();
        AnkamaAccountDto bot = login(null, LAUNCHER_URL);
        when(haapi.sendShieldCode("the-key")).thenReturn("proton.me");

        admin.resendShieldCode(bot.id());

        assertThat(admin.list().get(0).shieldCodeDomain()).isEqualTo("proton.me");
    }

    @Test
    void reauth_keepsTheExistingCertificate() {
        shieldAccount();
        AnkamaAccountDto bot = login(null, LAUNCHER_URL);
        when(haapi.validateShieldCode(anyString(), anyString(), anyString())).thenReturn(new Certificate(7L, "cert"));
        admin.submitShieldCode(bot.id(), "123456");
        when(haapi.exchangeCode(anyString(), anyString())).thenReturn(new Tokens("the-key", "again"));

        AnkamaAccountDto again = login(bot.id(), LAUNCHER_URL);

        assertThat(again.authState()).isEqualTo("READY");
        assertThat(store.get(bot.id()).getCertificateId()).isEqualTo(7L);
        assertThat(store.get(bot.id()).getCertificateEnc()).isEqualTo("enc:cert");
        verify(haapi).sendShieldCode("the-key"); // the first login only
    }

    @Test
    void dtos_neverCarryTheKeyOrRefreshToken() throws Exception {
        shieldAccount();
        AnkamaAccountDto bot = login(null, LAUNCHER_URL);
        when(haapi.validateShieldCode(anyString(), anyString(), anyString())).thenReturn(new Certificate(7L, "cert-body"));
        admin.submitShieldCode(bot.id(), "123456");
        AnkamaLoginStartDto start = admin.startLogin(null);

        JsonMapper mapper = JsonMapper.builder().build();
        String json = mapper.writeValueAsString(admin.list()) + mapper.writeValueAsString(start);

        assertThat(json).doesNotContain("the-key").doesNotContain("the-refresh").doesNotContain("cert-body")
                .doesNotContain(store.get(bot.id()).getHm1()).doesNotContain(store.get(bot.id()).getDeviceId())
                .doesNotContain("code_verifier");
    }

    @Test
    void update_editsFillOrderEnabledAndLimit_andRestartsWhenEnabledChanges() {
        AnkamaAccountDto bot = login(null, LAUNCHER_URL);
        store.get(bot.id()).setFriendLimitReached(true);

        AnkamaAccountDto dto = admin.update(bot.id(), Map.of("fillOrder", 3, "enabled", false,
                "friendLimitReached", false));

        assertThat(dto.fillOrder()).isEqualTo(3);
        assertThat(dto.enabled()).isFalse();
        assertThat(dto.friendLimitReached()).isFalse();
        verify(sessions, org.mockito.Mockito.times(2)).restart(bot.id()); // the login, then the disable
    }

    @Test
    void update_withAWrongFieldType_is400_andAnUnknownBotIs404() {
        AnkamaAccountDto bot = login(null, LAUNCHER_URL);

        assertThatThrownBy(() -> admin.update(bot.id(), Map.of("enabled", "yes")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> admin.update(UUID.randomUUID(), Map.of("enabled", true)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void delete_stopsTheSession_dropsTheLinks_disablesTheGetter_andTellsTheOwner() {
        AnkamaAccountDto bot = login(null, LAUNCHER_URL);
        UserAccount owner = new UserAccount();
        owner.setId(UUID.randomUUID());
        AnkamaFriendLink link = new AnkamaFriendLink();
        link.setUser(owner);
        link.setServiceAccount(store.get(bot.id()));
        when(links.findByServiceAccountId(bot.id())).thenReturn(List.of(link));
        GetterConfig config = new GetterConfig();
        config.setEnabled(true);
        when(getterConfigs.findByUserAndProvider(owner, GetterConfig.Provider.ANKAMA)).thenReturn(Optional.of(config));

        admin.delete(bot.id());

        verify(sessions).stop(bot.id());
        verify(links).deleteAll(List.of(link));
        assertThat(config.isEnabled()).isFalse();
        verify(accounts).delete(any());
        verify(publisher).publishAnkamaLinkChanged(eq(owner.getId()),
                eq(AnkamaLinkStateService.of(AnkamaLinkStateService.NONE)));
    }
}
