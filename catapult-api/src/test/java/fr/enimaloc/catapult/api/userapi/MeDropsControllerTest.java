package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.domain.account.ApiTokenScope;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.ApiSecurityConfig;
import fr.enimaloc.catapult.service.account.ApiTokenOwner;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.binding.GameBindingLookup;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.drops.DropCampaign;
import fr.enimaloc.catapult.service.drops.DropsCatalog;
import fr.enimaloc.catapult.service.drops.DropsGate;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = MeDropsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({ApiSecurityConfig.class, MeDropsControllerTest.Keys.class, ApiUserResolver.class})
@ActiveProfiles("test")
class MeDropsControllerTest {

    private static final String DROPS = "/api/me/drops";
    private static final String TOKEN = "catr_" + "t".repeat(43);

    @Autowired MockMvc mvc;
    @MockitoBean UserApiTokenService tokens;
    @MockitoBean UserAccountRepository accounts;
    @MockitoBean DropsGate gate;
    @MockitoBean DropsCatalog catalog;
    @MockitoBean GameStateService gameState;
    @MockitoBean GameBindingLookup bindings;

    private UserAccount user;
    private final DropCampaign aurora = new DropCampaign("e35e", "SEASON AURORA", "1282866981", "Delta Force",
            Instant.parse("2026-11-10T02:00:00Z"), Instant.parse("2026-11-25T23:59:59Z"), "https://example.test/aurora",
            "https://ttvdrops.example/media/a.png", false, Set.of());

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("42");
        when(accounts.findById(user.getId())).thenReturn(Optional.of(user));
        when(tokens.authenticate(TOKEN)).thenReturn(Optional.of(new ApiTokenOwner(user, ApiTokenScope.READ)));
        when(gate.isAvailableFor(any())).thenReturn(true);
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TOKEN);
    }

    @Test
    void currentCategory_runningCampaigns() throws Exception {
        DetectedGame detected = new DetectedGame("730", GameBinding.SourceType.STEAM, "Delta Force");
        GameBinding binding = new GameBinding();
        binding.setTwitchGameId("1282866981");
        when(gameState.getLastKnownGame(any())).thenReturn(Optional.of(detected));
        when(bindings.findByDetectedGame(any(), any())).thenReturn(Optional.of(binding));
        when(catalog.runningFor("42", "1282866981")).thenReturn(List.of(aurora));

        mvc.perform(authorized(get(DROPS).header("X-API-Version", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("e35e"))
                .andExpect(jsonPath("$[0].name").value("SEASON AURORA"))
                .andExpect(jsonPath("$[0].gameId").value("1282866981"))
                .andExpect(jsonPath("$[0].gameName").value("Delta Force"))
                .andExpect(jsonPath("$[0].endAt").value("2026-11-25T23:59:59Z"))
                .andExpect(jsonPath("$[0].detailsUrl").value("https://example.test/aurora"))
                .andExpect(jsonPath("$[0].imageUrl").value("https://ttvdrops.example/media/a.png"));
    }

    @Test
    void categoryIdParam_replacesTheCurrentCategory() throws Exception {
        when(catalog.runningFor("42", "1282866981")).thenReturn(List.of(aurora));

        mvc.perform(authorized(get(DROPS).param("categoryId", "1282866981").header("X-API-Version", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("e35e"));
        verify(gameState, never()).getLastKnownGame(any());
    }

    @Test
    void noGame_isEmpty() throws Exception {
        when(gameState.getLastKnownGame(any())).thenReturn(Optional.empty());

        mvc.perform(authorized(get(DROPS).header("X-API-Version", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void gated_403() throws Exception {
        when(gate.isAvailableFor(any())).thenReturn(false);

        mvc.perform(authorized(get(DROPS).header("X-API-Version", "2")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("feature_disabled"));
    }

    @TestConfiguration
    static class Keys {
        @Bean
        SecretKey jwtSecretKey() {
            return new SecretKeySpec(new byte[32], "HmacSHA256");
        }
    }
}
