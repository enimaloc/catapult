package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.api.ApiMeApiTokenController;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.CustomBindingViews;
import fr.enimaloc.catapult.api.userapi.CustomBindingsApiV2Controller;
import fr.enimaloc.catapult.common.dto.account.ApiTokenStatusDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ApiSecurityConfig really active, in both profiles: /api/v2/custom-bindings/** only takes the personal API token,
 * from anywhere (localhost included, which MockMvc requests come from), and nothing else gets through.
 */
class CustomBindingApiSecurityTest {

    private static final SecretKey KEY = new SecretKeySpec(new byte[32], "HmacSHA256");
    private static final String PERSONAL_TOKEN = "cat_" + "x".repeat(43);

    @WebMvcTest(
            controllers = {CustomBindingsApiV2Controller.class, ApiMeApiTokenController.class},
            excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
            excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
    @Import({ApiSecurityConfig.class, Keys.class, ApiUserResolver.class, CustomBindingViews.class})
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
            when(tokens.authenticate(PERSONAL_TOKEN)).thenReturn(Optional.of(user));
            when(customBindings.list(any())).thenReturn(List.of());
            when(customBindings.enabled(any())).thenReturn(true);
            when(tokens.status(any())).thenReturn(new ApiTokenStatusDto(true, Instant.EPOCH));
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

        @Test
        void missingToken_is401() throws Exception {
            mvc.perform(get("/api/v2/custom-bindings")).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v2/custom-bindings/art/activate")).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v2/custom-bindings/deactivate")).andExpect(status().isUnauthorized());
            verify(customBindings, never()).activate(any(), any());
            verify(customBindings, never()).deactivate(any());
        }

        @Test
        void invalidPersonalToken_is401() throws Exception {
            mvc.perform(post("/api/v2/custom-bindings/art/activate")
                            .header("Authorization", "Bearer cat_" + "y".repeat(43)))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v2/custom-bindings/art/activate").header("Authorization", PERSONAL_TOKEN))
                    .andExpect(status().isUnauthorized());
            verify(customBindings, never()).activate(any(), any());
        }

        @Test
        void widgetToken_isRejected() throws Exception {
            String widget = user.getWidgetToken().toString();
            mvc.perform(post("/api/v2/custom-bindings/art/activate").header("Authorization", "Bearer " + widget))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v2/custom-bindings/art/activate").param("uuid", widget))
                    .andExpect(status().isUnauthorized());
            verify(customBindings, never()).activate(any(), any());
        }

        @Test
        void jwt_isRejected() throws Exception {
            String jwt = jwt();
            // The same JWT is good elsewhere on the API...
            mvc.perform(get("/api/me/api-token").header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isOk());
            // ...but not here
            mvc.perform(get("/api/v2/custom-bindings").header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v2/custom-bindings/art/activate").header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isUnauthorized());
            verify(customBindings, never()).activate(any(), any());
        }

        @Test
        void personalToken_isUselessElsewhere() throws Exception {
            mvc.perform(get("/api/me/api-token").header("Authorization", "Bearer " + PERSONAL_TOKEN)
                            .with(r -> { r.setRemoteAddr("203.0.113.9"); return r; }))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @ActiveProfiles("test")
    class ProdChain extends Slice {}

    @Nested
    @ActiveProfiles("dev")
    class DevChain extends Slice {
        @Override
        @Test
        void personalToken_isUselessElsewhere() throws Exception {
            // The dev chain lets every request through to the controllers; a personal token is no JWT there either
            mvc.perform(get("/api/me/api-token").header("Authorization", "Bearer " + PERSONAL_TOKEN))
                    .andExpect(status().isUnauthorized());
        }
    }

    @TestConfiguration
    static class Keys {
        @Bean
        SecretKey jwtSecretKey() {
            return KEY;
        }
    }
}
