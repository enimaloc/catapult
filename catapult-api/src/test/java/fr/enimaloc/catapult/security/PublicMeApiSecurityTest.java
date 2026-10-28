package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.account.ApiTokenScope;
import fr.enimaloc.catapult.api.ApiMeApiTokenController;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.CustomBindingViews;
import fr.enimaloc.catapult.api.userapi.CustomBindingsApiV2Controller;
import fr.enimaloc.catapult.common.dto.account.ApiTokenStatusDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import fr.enimaloc.catapult.service.account.ApiTokenOwner;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ApiSecurityConfig really active, in both profiles: /api/me/** (and, until it goes, /api/v2/custom-bindings/**) only
 * takes a personal API token, from anywhere (localhost included, which MockMvc requests come from), and nothing else
 * gets through. A read-only token opens the GET routes only, and is the one token {@code ?token=} accepts.
 */
class PublicMeApiSecurityTest {

    private static final UUID SELF = UUID.randomUUID();
    private static final String OWN = "/api/internal/users/" + SELF;

    private static final SecretKey KEY = new SecretKeySpec(new byte[32], "HmacSHA256");
    private static final String PERSONAL_TOKEN = "cat_" + "x".repeat(43);
    private static final String READ_TOKEN = "catr_" + "r".repeat(43);

    /**
     * Stands in for the /api/me routes, which arrive later: answers with the caller's principal, so a test sees who
     * got through.
     */
    @RestController
    static class PublicMeStub {
        @GetMapping("/api/me/game")
        Map<String, Object> game(Authentication auth) {
            return Map.of("principal", auth.getPrincipal().toString());
        }

        @PutMapping("/api/me/custom-bindings/active")
        Map<String, Object> activate(Authentication auth, @RequestBody Map<String, String> body) {
            return Map.of("principal", auth.getPrincipal().toString(), "idOrName", body.get("idOrName"));
        }

        @DeleteMapping("/api/me/custom-bindings/active")
        ResponseEntity<Void> deactivate() {
            return ResponseEntity.noContent().build();
        }
    }

    @WebMvcTest(
            controllers = {CustomBindingsApiV2Controller.class, ApiMeApiTokenController.class},
            excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
            excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
    @Import({ApiSecurityConfig.class, Keys.class, ApiUserResolver.class, CustomBindingViews.class,
            PublicMeStub.class})
    abstract static class Slice {
        @Autowired MockMvc mvc;
        @MockitoBean UserApiTokenService tokens;
        @MockitoBean CustomBindingService customBindings;
        @MockitoBean UserAccountRepository accounts;
        @MockitoBean ChannelAccessService channelAccess;

        UserAccount user;

        @BeforeEach
        void member() {
            user = new UserAccount();
            user.setId(UUID.randomUUID());
            user.setTwitchUsername("enimaloc");
            user.setWidgetToken(UUID.randomUUID());
            when(accounts.findById(user.getId())).thenReturn(Optional.of(user));
            when(tokens.authenticate(PERSONAL_TOKEN)).thenReturn(Optional.of(new ApiTokenOwner(user, ApiTokenScope.FULL)));
            when(tokens.authenticate(READ_TOKEN)).thenReturn(Optional.of(new ApiTokenOwner(user, ApiTokenScope.READ)));
            when(customBindings.list(any())).thenReturn(List.of());
            when(customBindings.enabled(any())).thenReturn(true);
            when(tokens.status(any(), any())).thenReturn(new ApiTokenStatusDto(true, Instant.EPOCH));
        }

        /** A 401 whose body is a problem with the code {@code unauthorized} (CORS headers aside). */
        static ResultMatcher unauthorizedProblem() {
            return result -> {
                status().isUnauthorized().match(result);
                content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
                jsonPath("$.code").value("unauthorized").match(result);
            };
        }

        /** A 403 whose body is a problem with the code {@code forbidden}. */
        static ResultMatcher forbiddenProblem() {
            return result -> {
                status().isForbidden().match(result);
                content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
                jsonPath("$.code").value("forbidden").match(result);
            };
        }

        String jwt() {
            return new JwtService(KEY, -1).generateForUser(user, List.of("ROLE_USER", "ROLE_ADMIN"));
        }

        @Test
        void personalToken_isAccepted_onTheRealPath() throws Exception {
            mvc.perform(get("/api/v2/custom-bindings").header("Authorization", "Bearer " + PERSONAL_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        // --- /api/me: the full token opens everything, the read-only one the GET routes only ---

        private static final String ACTIVE = "/api/me/custom-bindings/active";

        @Test
        void fullToken_header_opensReadAndWrite() throws Exception {
            String bearer = "Bearer " + PERSONAL_TOKEN;
            mvc.perform(get("/api/me/game").header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.principal").value(user.getId().toString()));
            mvc.perform(put(ACTIVE).header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"idOrName\": \"art\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.idOrName").value("art"));
            mvc.perform(delete(ACTIVE).header("Authorization", bearer)).andExpect(status().isNoContent());
        }

        @Test
        void readToken_header_opensGet_andIs403OnPut() throws Exception {
            String bearer = "Bearer " + READ_TOKEN;
            mvc.perform(get("/api/me/game").header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.principal").value(user.getId().toString()));
            mvc.perform(put(ACTIVE).header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"idOrName\": \"art\"}"))
                    .andExpect(forbiddenProblem());
            mvc.perform(delete(ACTIVE).header("Authorization", bearer)).andExpect(forbiddenProblem());
        }

        @Test
        void readToken_onTheV2Writes_is403() throws Exception {
            mvc.perform(post("/api/v2/custom-bindings/art/activate").header("Authorization", "Bearer " + READ_TOKEN))
                    .andExpect(forbiddenProblem());
            mvc.perform(post("/api/v2/custom-bindings/deactivate").header("Authorization", "Bearer " + READ_TOKEN))
                    .andExpect(forbiddenProblem());
            verify(customBindings, never()).activate(any(), any());
            verify(customBindings, never()).deactivate(any());
        }

        @Test
        void readToken_query_opensGet_whenNoAuthorizationHeader() throws Exception {
            mvc.perform(get("/api/me/game").queryParam("token", READ_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.principal").value(user.getId().toString()));
        }

        @Test
        void fullToken_query_is401() throws Exception {
            mvc.perform(get("/api/me/game").queryParam("token", PERSONAL_TOKEN)).andExpect(unauthorizedProblem());
            mvc.perform(get("/api/v2/custom-bindings").queryParam("token", PERSONAL_TOKEN))
                    .andExpect(unauthorizedProblem());
        }

        @Test
        void invalidHeader_withValidQueryToken_is401() throws Exception {
            mvc.perform(get("/api/me/game").queryParam("token", READ_TOKEN)
                            .header("Authorization", "Bearer cat_" + "y".repeat(43)))
                    .andExpect(unauthorizedProblem());
            mvc.perform(get("/api/me/game").queryParam("token", READ_TOKEN).header("Authorization", "Basic abc"))
                    .andExpect(unauthorizedProblem());
        }

        @Test
        void sessionJwt_is401_onPublicMe() throws Exception {
            mvc.perform(get("/api/me/game").header("Authorization", "Bearer " + jwt()))
                    .andExpect(unauthorizedProblem());
        }

        @Test
        void preflight_allowsGetPutDelete_fromAnyOrigin() throws Exception {
            for (String method : List.of("GET", "PUT", "DELETE")) {
                mvc.perform(options(ACTIVE)
                                .header("Origin", ORIGIN)
                                .header("Access-Control-Request-Method", method)
                                .header("Access-Control-Request-Headers", "authorization, content-type"))
                        .andExpect(status().is2xxSuccessful())
                        .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                        .andExpect(header().string("Access-Control-Allow-Methods", ALLOWED_METHODS))
                        .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
            }
        }

        // --- /api/v2/custom-bindings, until it goes ---

        @Test
        void missingToken_is401() throws Exception {
            mvc.perform(get("/api/v2/custom-bindings")).andExpect(unauthorizedProblem());
            mvc.perform(post("/api/v2/custom-bindings/art/activate")).andExpect(unauthorizedProblem());
            mvc.perform(post("/api/v2/custom-bindings/deactivate")).andExpect(unauthorizedProblem());
            verify(customBindings, never()).activate(any(), any());
            verify(customBindings, never()).deactivate(any());
        }

        @Test
        void invalidPersonalToken_is401() throws Exception {
            mvc.perform(post("/api/v2/custom-bindings/art/activate")
                            .header("Authorization", "Bearer cat_" + "y".repeat(43)))
                    .andExpect(unauthorizedProblem());
            mvc.perform(post("/api/v2/custom-bindings/art/activate").header("Authorization", PERSONAL_TOKEN))
                    .andExpect(unauthorizedProblem());
            verify(customBindings, never()).activate(any(), any());
        }

        @Test
        void widgetToken_isRejected() throws Exception {
            String widget = user.getWidgetToken().toString();
            mvc.perform(post("/api/v2/custom-bindings/art/activate").header("Authorization", "Bearer " + widget))
                    .andExpect(unauthorizedProblem());
            mvc.perform(post("/api/v2/custom-bindings/art/activate").param("uuid", widget))
                    .andExpect(unauthorizedProblem());
            verify(customBindings, never()).activate(any(), any());
        }

        @Test
        void jwt_isRejected() throws Exception {
            String jwt = jwt();
            // The same JWT is good elsewhere on the API...
            mvc.perform(get(OWN + "/api-token").header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isOk());
            // ...but not here
            mvc.perform(get("/api/v2/custom-bindings").header("Authorization", "Bearer " + jwt))
                    .andExpect(unauthorizedProblem());
            mvc.perform(post("/api/v2/custom-bindings/art/activate").header("Authorization", "Bearer " + jwt))
                    .andExpect(unauthorizedProblem());
            verify(customBindings, never()).activate(any(), any());
        }

        @Test
        void personalToken_isUselessElsewhere() throws Exception {
            mvc.perform(get(OWN + "/api-token").header("Authorization", "Bearer " + PERSONAL_TOKEN)
                            .with(r -> { r.setRemoteAddr("203.0.113.9"); return r; }))
                    .andExpect(unauthorizedProblem());
        }

        // --- CORS: a page of any origin (Twitchat) calls this API with its token, never with cookies ---

        private static final String ORIGIN = "https://example.org";
        private static final String ALLOWED_METHODS = "GET,POST,PUT,DELETE,OPTIONS";

        @Test
        void preflight_fromAnyOrigin_isAnsweredWithoutAuthentication() throws Exception {
            for (String path : List.of("/api/v2/custom-bindings", "/api/v2/custom-bindings/art/activate")) {
                mvc.perform(options(path)
                                .header("Origin", ORIGIN)
                                .header("Access-Control-Request-Method", "POST")
                                .header("Access-Control-Request-Headers", "authorization, content-type"))
                        .andExpect(status().is2xxSuccessful())
                        .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                        .andExpect(header().string("Access-Control-Allow-Methods", ALLOWED_METHODS))
                        .andExpect(header().string("Access-Control-Allow-Headers",
                                org.hamcrest.Matchers.containsStringIgnoringCase("authorization")))
                        .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
            }
        }

        @Test
        void actualRequests_fromAnyOrigin_carryTheCorsHeaders_refusalsIncluded() throws Exception {
            mvc.perform(post("/api/v2/custom-bindings/deactivate").header("Origin", ORIGIN)
                            .header("Authorization", "Bearer " + PERSONAL_TOKEN))
                    .andExpect(status().is2xxSuccessful())
                    .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
            verify(customBindings).deactivate(user);

            mvc.perform(post("/api/v2/custom-bindings/deactivate").header("Origin", ORIGIN)
                            .header("Authorization", "Bearer cat_" + "y".repeat(43)))
                    .andExpect(unauthorizedProblem())
                    .andExpect(header().string("Access-Control-Allow-Origin", "*"));
        }

        @Test
        void aRefusalAnsweredByTheController_carriesTheCorsHeaders() throws Exception {
            mvc.perform(post("/api/v2/custom-bindings/unknown/activate").header("Origin", ORIGIN)
                            .header("Authorization", "Bearer " + PERSONAL_TOKEN))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("not_found"))
                    .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        }

        @Test
        void aSessionCookieAlone_is401() throws Exception {
            mvc.perform(post("/api/v2/custom-bindings/deactivate").header("Origin", ORIGIN)
                            .cookie(new Cookie("CATAPULT_API_SESSION", "s1"), new Cookie("JSESSIONID", "s2")))
                    .andExpect(unauthorizedProblem());
            verify(customBindings, never()).deactivate(any());
        }

        @Test
        void otherRoutes_getNoCors() throws Exception {
            mvc.perform(get(OWN + "/api-token").header("Origin", ORIGIN).header("Authorization", "Bearer " + jwt()))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            mvc.perform(options(OWN + "/api-token")
                            .header("Origin", ORIGIN)
                            .header("Access-Control-Request-Method", "GET")
                            .header("Access-Control-Request-Headers", "authorization"))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }

    @Nested
    @ActiveProfiles("test")
    class ProdChain extends Slice {}

    @Nested
    @ActiveProfiles("dev")
    class DevChain extends Slice {}

    @TestConfiguration
    static class Keys {
        @Bean
        SecretKey jwtSecretKey() {
            return KEY;
        }
    }
}
