package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.AccountDto;
import fr.enimaloc.catapult.domain.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.MinecraftServiceAccount;
import fr.enimaloc.catapult.repository.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.MinecraftService;
import fr.enimaloc.catapult.service.MinecraftTokenService;
import fr.enimaloc.catapult.service.MsaAuthClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The admin's Minecraft service-account pool: device-code enrolment, re-auth, edits and removal. */
class ApiAdminMinecraftAccountsFlowsTest {

    private final MsaAuthClient msa = mock(MsaAuthClient.class);
    private final MinecraftTokenService tokens = mock(MinecraftTokenService.class);
    private final MinecraftService minecraft = mock(MinecraftService.class);
    private final MinecraftServiceAccountRepository accounts = mock(MinecraftServiceAccountRepository.class);
    private final MinecraftFriendLinkRepository links = mock(MinecraftFriendLinkRepository.class);
    private final TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    private final ApiAdminMinecraftAccountsController controller =
            new ApiAdminMinecraftAccountsController(msa, tokens, minecraft, accounts, links, encryption);

    private MinecraftServiceAccount account;

    @BeforeEach
    void setUp() {
        account = new MinecraftServiceAccount();
        account.setId(UUID.randomUUID());
        account.setLabel("Pool #1");
        account.setMinecraftUsername("Steve");
        account.setEnabled(false);
        when(accounts.findById(any())).thenReturn(Optional.empty());
        when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
        when(accounts.save(any())).thenAnswer(call -> call.getArgument(0));
        when(links.countByServiceAccount(account)).thenReturn(3L);
        when(encryption.encrypt("msa-refresh")).thenReturn("enc-refresh");
    }

    private void chainValidates(String username) {
        when(tokens.validateChain("enc-refresh"))
                .thenReturn(Optional.of(new MinecraftTokenService.ValidatedChain("mc-token", "rotated", 3600)));
        when(minecraft.getMinecraftProfileName("mc-token")).thenReturn(username);
    }

    private static MsaAuthClient.MsaTokens msaTokens() {
        return new MsaAuthClient.MsaTokens("access", "msa-refresh", 3600);
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    @Test
    void list_includesEachAccountsLinkCount() {
        when(accounts.findAll()).thenReturn(List.of(account));

        assertThat(controller.list()).containsExactly(
                new AccountDto(account.getId(), "Pool #1", "Steve", 0, false, false, 3));
    }

    @Test
    void deviceCode_isStarted_andMicrosoftRefusalsReachTheAdmin() {
        MsaAuthClient.DeviceCodeStart start = new MsaAuthClient.DeviceCodeStart("dc", "ABCD", "https://microsoft.com/link", 5, 900);
        when(msa.startDeviceCode()).thenReturn(start)
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                        "AADSTS70002".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        assertThat(controller.startDeviceCode().getBody()).isEqualTo(start);
        ResponseEntity<?> refused = controller.startDeviceCode();
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(refused.getBody()).isEqualTo(Map.of("message", "Device-code Microsoft refusé: AADSTS70002"));
    }

    @Test
    void create_needsADeviceCode() {
        assertStatus(() -> controller.create(Map.of()), HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.create(Map.of("deviceCode", " ")), HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_usesTheDefaultLabel_andFailsWhenTheChainDoesNot() {
        when(msa.pollDeviceCode("dc")).thenReturn(Optional.of(msaTokens()));
        when(accounts.count()).thenReturn(2L);
        chainValidates("Alex");

        ResponseEntity<?> created = controller.create(Map.of("deviceCode", "dc"));

        assertThat(created.getBody()).isInstanceOfSatisfying(AccountDto.class, dto -> {
            assertThat(dto.label()).isEqualTo("Compte Minecraft");
            assertThat(dto.minecraftUsername()).isEqualTo("Alex");
            assertThat(dto.fillOrder()).isEqualTo(2);
            assertThat(dto.linkCount()).isZero();
        });

        when(tokens.validateChain("enc-refresh")).thenReturn(Optional.empty());
        assertStatus(() -> controller.create(Map.of("deviceCode", "dc")), HttpStatus.BAD_GATEWAY);
        when(tokens.validateChain("enc-refresh")).thenThrow(new ResourceAccessException("down"));
        assertStatus(() -> controller.create(Map.of("deviceCode", "dc")), HttpStatus.BAD_GATEWAY);
    }

    @Test
    void reauth_replacesTheRefreshToken_reEnables_andEvictsTheCache() {
        when(msa.pollDeviceCode("dc")).thenReturn(Optional.of(msaTokens()));
        chainValidates("Steve2");

        ResponseEntity<?> response = controller.reauth(account.getId(), Map.of("deviceCode", "dc"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(account.getMsaRefreshToken()).isEqualTo("rotated");
        assertThat(account.getMinecraftUsername()).isEqualTo("Steve2");
        assertThat(account.isEnabled()).isTrue();
        verify(tokens).evict(account.getId());
        assertThat(((AccountDto) response.getBody()).linkCount()).isEqualTo(3);
    }

    @Test
    void reauth_staysPending_untilTheCodeIsConfirmed() {
        when(msa.pollDeviceCode("dc")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.reauth(account.getId(), Map.of("deviceCode", "dc"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isEqualTo(Map.of("status", "PENDING"));
    }

    @Test
    void reauth_needsAKnownAccountAndADeviceCode() {
        assertStatus(() -> controller.reauth(UUID.randomUUID(), Map.of("deviceCode", "dc")), HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.reauth(account.getId(), Map.of()), HttpStatus.BAD_REQUEST);
    }

    @Test
    void update_appliesTheGivenFields() {
        AccountDto dto = controller.update(account.getId(), Map.of("enabled", true, "friendLimitReached", true, "fillOrder", 4));

        assertThat(dto).isEqualTo(new AccountDto(account.getId(), "Pool #1", "Steve", 4, true, true, 3));
        assertThat(account.getUpdatedAt()).isNotNull();

        controller.update(account.getId(), Map.of());
        assertThat(account.getFillOrder()).isEqualTo(4);
    }

    @Test
    void update_rejectsWronglyTypedFields() {
        assertStatus(() -> controller.update(account.getId(), Map.of("enabled", "yes")), HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.update(account.getId(), Map.of("friendLimitReached", 1)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.update(account.getId(), Map.of("fillOrder", "4")), HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.update(UUID.randomUUID(), Map.of()), HttpStatus.NOT_FOUND);
        verify(accounts, never()).save(any());
    }

    @Test
    void delete_unlinkedAccounts() {
        when(links.findByServiceAccount(account)).thenReturn(List.of());

        assertThat(controller.delete(account.getId()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(tokens).evict(account.getId());
        verify(accounts).delete(account);

        when(links.findByServiceAccount(account)).thenReturn(List.of(new MinecraftFriendLink()));
        assertThat(controller.delete(account.getId()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertStatus(() -> controller.delete(UUID.randomUUID()), HttpStatus.NOT_FOUND);
    }
}
