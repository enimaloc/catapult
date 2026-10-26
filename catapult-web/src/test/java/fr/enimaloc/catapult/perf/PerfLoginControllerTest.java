package fr.enimaloc.catapult.perf;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.enimaloc.catapult.common.dto.TokenResponse;
import fr.enimaloc.catapult.controller.SseStreams;
import fr.enimaloc.catapult.security.SessionLogin;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PerfLoginController.class, properties = "catapult.perf.enabled=true")
@ActiveProfiles("perf")
@Import({WebSecurityConfig.class, SessionLogin.class, PerfGuard.class})
class PerfLoginControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiClient apiClient;
    @MockitoBean ApiService apiService;
    @MockitoBean SseStreams sse;

    @Test
    void logsInAndOpensTheChannel() throws Exception {
        String jwt = jwt("[\"ROLE_USER\"]");
        answer("perf-streamer-001", new ApiClient.Result<>(200, new TokenResponse(jwt)));

        mvc.perform(get("/perf/login").param("username", "perf-streamer-001"))
                .andExpect(redirectedUrl("/channel/perf-streamer-001"))
                .andExpect(request().sessionAttribute(ApiClient.SESSION_JWT_KEY, jwt));
    }

    @Test
    void adminOpensAdmin() throws Exception {
        String jwt = jwt("[\"ROLE_USER\",\"ROLE_ADMIN\"]");
        answer("perf-admin", new ApiClient.Result<>(200, new TokenResponse(jwt)));

        mvc.perform(get("/perf/login").param("username", "perf-admin"))
                .andExpect(redirectedUrl("/admin"))
                .andExpect(request().sessionAttribute(ApiClient.SESSION_JWT_KEY, jwt));
    }

    @Test
    void unknownAccountIs404() throws Exception {
        answer("nobody", new ApiClient.Result<>(404, null));

        mvc.perform(get("/perf/login").param("username", "nobody"))
                .andExpect(status().isNotFound())
                .andExpect(request().sessionAttributeDoesNotExist(ApiClient.SESSION_JWT_KEY));
    }

    @Test
    void anUnreachableApiIs502() throws Exception {
        answer("perf-streamer-001", new ApiClient.Result<>(502, null));

        mvc.perform(get("/perf/login").param("username", "perf-streamer-001"))
                .andExpect(status().isBadGateway())
                .andExpect(request().sessionAttributeDoesNotExist(ApiClient.SESSION_JWT_KEY));
    }

    @Test
    void anAnswerWithoutATokenIs502() throws Exception {
        answer("perf-streamer-001", new ApiClient.Result<>(200, null));

        mvc.perform(get("/perf/login").param("username", "perf-streamer-001"))
                .andExpect(status().isBadGateway());
    }

    private void answer(String username, ApiClient.Result<TokenResponse> result) {
        when(apiClient.callAs(eq(HttpMethod.POST), eq(PerfLoginController.API_LOGIN), isNull(),
                eq(TokenResponse.class), eq(username))).thenReturn(result);
    }

    /** A JWT shaped as catapult-api's, with {@code roles} (unsigned: the session only reads it). */
    private static String jwt(String roles) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String claims = "{\"sub\":\"00000000-0000-0000-0000-000000000001\",\"roles\":" + roles + "}";
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }
}
