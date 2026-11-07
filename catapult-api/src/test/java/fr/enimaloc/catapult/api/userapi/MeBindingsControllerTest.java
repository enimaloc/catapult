package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.domain.account.ApiTokenScope;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.ApiSecurityConfig;
import fr.enimaloc.catapult.service.account.ApiTokenOwner;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = MeBindingsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({ApiSecurityConfig.class, MeBindingsControllerTest.Keys.class, ApiUserResolver.class})
@ActiveProfiles("test")
class MeBindingsControllerTest {

    private static final String READ_TOKEN = "catr_" + "r".repeat(43);

    @Autowired MockMvc mvc;
    @MockitoBean UserApiTokenService tokens;
    @MockitoBean UserAccountRepository accounts;
    @MockitoBean GameInfoAssembler games;

    private UserAccount user;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(accounts.findById(user.getId())).thenReturn(Optional.of(user));
        when(tokens.authenticate(READ_TOKEN)).thenReturn(Optional.of(new ApiTokenOwner(user, ApiTokenScope.READ)));
    }

    @Test
    void meBindings_returnsTheOwnersBinding() throws Exception {
        UUID bindingId = UUID.randomUUID();
        when(games.catapult(eq(bindingId), eq(user), any(Locale.class))).thenReturn(Optional.of(
                new CatapultDetailResponse(Instant.EPOCH, Instant.EPOCH, GameBinding.SourceType.STEAM, "400",
                        "Portal", "1234", "Portal", false, true, Set.of(), true, false, Set.of("gore"),
                        Set.of("Gore"))));

        mvc.perform(get("/api/me/bindings/{id}", bindingId).header("Authorization", "Bearer " + READ_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value("400"))
                .andExpect(jsonPath("$.twitchGameName").value("Portal"))
                .andExpect(jsonPath("$.twLabels[0]").value("Gore"));
    }

    @Test
    void meBindings_ofAnotherAccount_is404() throws Exception {
        UUID someoneElses = UUID.randomUUID();
        when(games.catapult(eq(someoneElses), eq(user), any(Locale.class))).thenReturn(Optional.empty());

        mvc.perform(get("/api/me/bindings/{id}", someoneElses).queryParam("token", READ_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @TestConfiguration
    static class Keys {
        @Bean
        SecretKey jwtSecretKey() {
            return new SecretKeySpec(new byte[32], "HmacSHA256");
        }
    }
}
