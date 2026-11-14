package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.error.ProblemAdvice;
import fr.enimaloc.catapult.common.dto.admin.AnkamaAccountDto;
import fr.enimaloc.catapult.common.dto.admin.AnkamaLoginStartDto;
import fr.enimaloc.catapult.service.ankama.AnkamaServiceAccountAdmin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP statuses of the Ankama pool admin. Admin-only access is the {@code /admin/**} rule of ApiSecurityConfig (see
 * the access matrix), not something the controller checks.
 */
class ApiAdminAnkamaAccountsControllerTest {

    private static final String BASE = "/api/internal/admin/ankama-accounts";

    private final AnkamaServiceAccountAdmin admin = mock(AnkamaServiceAccountAdmin.class);
    private final UUID id = UUID.randomUUID();
    private final AnkamaAccountDto dto = new AnkamaAccountDto(id, "Bot#0001", "CODE_REQUIRED", "DISABLED", 0, false,
            true, 3, "gmail.com");
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ApiAdminAnkamaAccountsController(admin))
                .setControllerAdvice(new ProblemAdvice()).build();
    }

    @Test
    void list_returnsTheAccounts() throws Exception {
        when(admin.list()).thenReturn(List.of(dto));

        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayTag").value("Bot#0001"))
                .andExpect(jsonPath("$[0].shieldCodeDomain").value("gmail.com"))
                .andExpect(jsonPath("$[0].friendCount").value(3));
    }

    @Test
    void startLogin_acceptsAnEmptyBody_orAnAccountId() throws Exception {
        AnkamaLoginStartDto start = new AnkamaLoginStartDto(UUID.randomUUID(), "https://auth.ankama.com/x");
        when(admin.startLogin(any())).thenReturn(start);

        mvc.perform(post(BASE + "/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizeUrl").value("https://auth.ankama.com/x"))
                .andExpect(jsonPath("$.loginId").value(start.loginId().toString()));
        verify(admin).startLogin(null);

        mvc.perform(post(BASE + "/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + id + "\"}"))
                .andExpect(status().isOk());
        verify(admin).startLogin(id);
    }

    @Test
    void startLogin_withABadAccountId_is400() throws Exception {
        mvc.perform(post(BASE + "/login").contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void completeLogin_returnsTheAccount() throws Exception {
        UUID loginId = UUID.randomUUID();
        when(admin.completeLogin(eq(loginId), eq("http://127.0.0.1:9001/authorized?code=c"))).thenReturn(dto);

        mvc.perform(post(BASE + "/login/{loginId}", loginId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"redirectUrl\":\"http://127.0.0.1:9001/authorized?code=c\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authState").value("CODE_REQUIRED"));
    }

    @Test
    void completeLogin_refused_is400WithTheMessage() throws Exception {
        when(admin.completeLogin(any(), any())).thenThrow(new IllegalArgumentException("not the launcher redirect URL"));

        mvc.perform(post(BASE + "/login/{loginId}", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"redirectUrl\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("not the launcher redirect URL"));
    }

    @Test
    void code_isAccepted_andAWrongOneIs400WithAnkamasMessage() throws Exception {
        mvc.perform(post(BASE + "/{id}/code", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"257146\"}"))
                .andExpect(status().isAccepted());
        verify(admin).submitShieldCode(id, "257146");

        doThrow(new IllegalArgumentException("Invalid security code")).when(admin).submitShieldCode(any(), any());
        mvc.perform(post(BASE + "/{id}/code", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid security code"));
    }

    @Test
    void resend_isAccepted_andAnUnknownBotIs404() throws Exception {
        mvc.perform(post(BASE + "/{id}/code/resend", id)).andExpect(status().isAccepted());
        verify(admin).resendShieldCode(id);

        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(admin).resendShieldCode(any());
        mvc.perform(post(BASE + "/{id}/code/resend", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void patch_updates_andDeleteReturns204() throws Exception {
        when(admin.update(eq(id), any())).thenReturn(dto);

        mvc.perform(patch(BASE + "/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{\"fillOrder\":3}"))
                .andExpect(status().isOk());
        verify(admin).update(id, Map.of("fillOrder", 3));
        mvc.perform(delete(BASE + "/{id}", id)).andExpect(status().isNoContent());
        verify(admin).delete(id);
    }
}
