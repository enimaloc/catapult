package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.debug.ClientExchangeBatch;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ClientDebugController.class)
@Import(WebSecurityConfig.class)
class ClientDebugControllerTest {

    private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final String BATCH = """
            {"source":"widget","sentAt":"2026-11-20T12:00:00Z","entries":[
              {"service":"obs","direction":"OUT","operation":"GetSceneList","at":"2026-11-20T11:59:58Z",
               "durationMs":12,"status":100,"request":{"requestType":"GetSceneList"},"response":{"scenes":[]}}]}""";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;

    @Test
    void meRelaysTheBatchAsPage() throws Exception {
        when(apiService.currentUserId()).thenReturn(ME);
        when(apiService.postClientDebugExchanges(eq(ME), any())).thenReturn(new ApiClient.Result<>(204, null));

        mvc.perform(post("/me/debug/client-exchanges").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BATCH))
                .andExpect(status().isNoContent());

        ArgumentCaptor<ClientExchangeBatch> relayed = ArgumentCaptor.forClass(ClientExchangeBatch.class);
        verify(apiService).postClientDebugExchanges(eq(ME), relayed.capture());
        // Whatever the browser says, the page is the source
        assertThat(relayed.getValue().source()).isEqualTo("page");
        assertThat(relayed.getValue().sentAt()).isEqualTo(Instant.parse("2026-11-20T12:00:00Z"));
        assertThat(relayed.getValue().entries()).singleElement().satisfies(e -> {
            assertThat(e.operation()).isEqualTo("GetSceneList");
            assertThat(e.status()).isEqualTo(100);
            assertThat(e.request()).containsEntry("requestType", "GetSceneList");
        });
    }

    @Test
    void meRequiresALogin() throws Exception {
        mvc.perform(post("/me/debug/client-exchanges").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BATCH))
                .andExpect(status().isUnauthorized());

        verify(apiService, never()).postClientDebugExchanges(any(), any());
    }

    @Test
    void relays409WhenTheMemberLeftDebug() throws Exception {
        when(apiService.currentUserId()).thenReturn(ME);
        when(apiService.postClientDebugExchanges(eq(ME), any())).thenReturn(new ApiClient.Result<>(409, null));

        mvc.perform(post("/me/debug/client-exchanges").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BATCH))
                .andExpect(status().isConflict());
    }

    @Test
    void anAdminImpersonatingAMemberRecordsNothingForThem() throws Exception {
        when(apiService.currentUserId()).thenReturn(ME);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ImpersonationController.ORIGINAL_JWT_KEY, "admin-jwt");

        mvc.perform(post("/me/debug/client-exchanges").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BATCH))
                .andExpect(status().isConflict());

        verify(apiService, never()).postClientDebugExchanges(any(), any());
    }

    @Test
    void aBodyThatIsNoBatchIsRefusedUnrelayed() throws Exception {
        when(apiService.currentUserId()).thenReturn(ME);

        mvc.perform(post("/me/debug/client-exchanges").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("nope"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/me/debug/client-exchanges").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(status().isBadRequest());

        verify(apiService, never()).postClientDebugExchanges(any(), any());
    }

    @Test
    void aBodyOverTheLimitIsRefusedUnrelayed() throws Exception {
        when(apiService.currentUserId()).thenReturn(ME);
        byte[] big = new byte[ClientDebugBatches.MAX_BYTES + 1];
        java.util.Arrays.fill(big, (byte) ' ');

        mvc.perform(post("/me/debug/client-exchanges").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isBadRequest());

        verify(apiService, never()).postClientDebugExchanges(any(), any());
    }

    @Test
    void aBodyAtTheLimitIsRelayed() throws Exception {
        when(apiService.currentUserId()).thenReturn(ME);
        when(apiService.postClientDebugExchanges(eq(ME), any())).thenReturn(new ApiClient.Result<>(204, null));
        // Padded with spaces, which JSON ignores, up to the limit exactly
        String padded = BATCH + " ".repeat(ClientDebugBatches.MAX_BYTES - BATCH.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

        mvc.perform(post("/me/debug/client-exchanges").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(padded))
                .andExpect(status().isNoContent());
    }

    @Test
    void meNeedsTheCsrfToken() throws Exception {
        when(apiService.currentUserId()).thenReturn(ME);

        mvc.perform(post("/me/debug/client-exchanges").contentType(MediaType.APPLICATION_JSON).content(BATCH))
                .andExpect(status().isForbidden());

        verify(apiService, never()).postClientDebugExchanges(any(), any());
    }
}
