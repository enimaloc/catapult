package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.error.ProblemAdvice;
import fr.enimaloc.catapult.common.dto.admin.EpicAccountDto;
import fr.enimaloc.catapult.service.epic.EpicServiceAccountAdmin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
 * HTTP statuses of the Epic pool admin. Admin-only access is the {@code /admin/**} rule of ApiSecurityConfig (see
 * the access matrix), not something the controller checks.
 */
class ApiAdminEpicAccountsControllerTest {

    private static final String BASE = "/api/internal/admin/epic-accounts";

    private final EpicServiceAccountAdmin admin = mock(EpicServiceAccountAdmin.class);
    private final UUID id = UUID.randomUUID();
    private final EpicAccountDto dto = new EpicAccountDto(id, "Catapult-1", "READY", "DISABLED", 0, false, true, 3,
            Instant.parse("2027-11-15T10:00:00Z"), false);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ApiAdminEpicAccountsController(admin))
                .setControllerAdvice(new ProblemAdvice()).build();
    }

    @Test
    void list_returnsTheAccounts_andTheCodeUrl() throws Exception {
        when(admin.list()).thenReturn(List.of(dto));

        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts[0].displayName").value("Catapult-1"))
                .andExpect(jsonPath("$.accounts[0].friendCount").value(3))
                .andExpect(jsonPath("$.accounts[0].refreshExpiresSoon").value(false))
                .andExpect(jsonPath("$.codeUrl").value("https://www.epicgames.com/id/api/redirect"
                        + "?clientId=34a02cf8f4414e29b15921876da36f9a&responseType=code"));
    }

    @Test
    void post_addsABot_orReauthenticatesOne() throws Exception {
        when(admin.addOrReauth(any(), any())).thenReturn(dto);

        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"the-paste\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authState").value("READY"));
        verify(admin).addOrReauth(eq("the-paste"), isNull());

        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"the-paste\",\"accountId\":\"" + id + "\"}"))
                .andExpect(status().isOk());
        verify(admin).addOrReauth("the-paste", id);
    }

    @Test
    void post_refused_is400WithTheMessage_andABadAccountIdToo() throws Exception {
        when(admin.addOrReauth(any(), any()))
                .thenThrow(new IllegalArgumentException("this code expired or was already used, get a new one"));

        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("this code expired or was already used, get a new one"));
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"x\",\"accountId\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("accountId is not a valid id"));
    }

    @Test
    void post_forAnUnknownBot_is404() throws Exception {
        when(admin.addOrReauth(any(), any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"x\",\"accountId\":\"" + id + "\"}"))
                .andExpect(status().isNotFound());
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
