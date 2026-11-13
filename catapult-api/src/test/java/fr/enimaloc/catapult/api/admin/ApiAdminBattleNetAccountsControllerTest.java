package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.error.ProblemAdvice;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.repository.battlenet.BattleNetServiceAccountRepository;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.battlenet.BattleNetServiceAccountAdmin;
import fr.enimaloc.catapult.service.battlenet.BattleNetSessionManager;
import fr.enimaloc.catapult.service.battlenet.login.BattleNetLoginCoordinator;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP statuses of the Battle.net pool admin. Admin-only access is the {@code /admin/**} rule of
 * ApiSecurityConfig (403 for anyone else, see the access matrix), not something the controller checks.
 */
class ApiAdminBattleNetAccountsControllerTest {

    private final BattleNetServiceAccountRepository accounts = mock(BattleNetServiceAccountRepository.class);
    private final BattleNetFriendLinkRepository links = mock(BattleNetFriendLinkRepository.class);
    private final TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    private final BattleNetLoginCoordinator coordinator = mock(BattleNetLoginCoordinator.class);
    private final BattleNetSessionManager sessions = mock(BattleNetSessionManager.class);
    private final BattleNetServiceAccount account = new BattleNetServiceAccount();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        account.setId(UUID.randomUUID());
        account.setEmail("bot-mailbox@example.com");
        account.setPasswordEnc("enc:pw");
        when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
        when(accounts.findAll()).thenReturn(List.of(account));
        when(accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        when(coordinator.startInteractive(any())).thenReturn(new CompletableFuture<>());
        when(sessions.sessionState(any())).thenReturn("DISABLED");
        mvc = MockMvcBuilders.standaloneSetup(new ApiAdminBattleNetAccountsController(
                        new BattleNetServiceAccountAdmin(accounts, links, encryption, coordinator, sessions,
                                mock(GetterConfigRepository.class), mock(ChannelEventPublisher.class))))
                .setControllerAdvice(new ProblemAdvice()).build();
    }

    @Test
    void listReturnsTheAccountsWithoutSecrets() throws Exception {
        mvc.perform(get("/api/internal/admin/battlenet-accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("b***@example.com"))
                .andExpect(jsonPath("$[0].label").doesNotExist())
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].passwordEnc").doesNotExist());
    }

    @Test
    void createReturns202() throws Exception {
        mvc.perform(post("/api/internal/admin/battlenet-accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.c\",\"password\":\"pw\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.authState").value("NEW"))
                .andExpect(jsonPath("$.displayName").value("a***@b.c"));
    }

    @Test
    void createWithoutPasswordIs400() throws Exception {
        mvc.perform(post("/api/internal/admin/battlenet-accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.c\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void codeIsAccepted() throws Exception {
        mvc.perform(post("/api/internal/admin/battlenet-accounts/{id}/code", account.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void codeWithNoLoginWaitingIs409() throws Exception {
        doThrow(new IllegalStateException("none")).when(coordinator).submitCode(any(), any());

        mvc.perform(post("/api/internal/admin/battlenet-accounts/{id}/code", account.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownAccountIs404() throws Exception {
        mvc.perform(post("/api/internal/admin/battlenet-accounts/{id}/code", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/internal/admin/battlenet-accounts/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void reauthReturns202() throws Exception {
        mvc.perform(post("/api/internal/admin/battlenet-accounts/{id}/reauth", account.getId()))
                .andExpect(status().isAccepted());
    }

    @Test
    void patchUpdatesAndDeleteReturns204() throws Exception {
        mvc.perform(patch("/api/internal/admin/battlenet-accounts/{id}", account.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"renamed\",\"fillOrder\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fillOrder").value(3))
                .andExpect(jsonPath("$.displayName").value("b***@example.com")) // the legacy label is ignored
                .andExpect(jsonPath("$.label").doesNotExist());
        mvc.perform(delete("/api/internal/admin/battlenet-accounts/{id}", account.getId()))
                .andExpect(status().isNoContent());
    }
}
