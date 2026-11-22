package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.domain.account.ApiTokenScope;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.ApiSecurityConfig;
import fr.enimaloc.catapult.service.account.ApiTokenOwner;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunIdentity;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunIdentityResolver;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunPersonalService;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunRun;
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
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = MeSpeedrunController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({ApiSecurityConfig.class, MeSpeedrunControllerTest.Keys.class, ApiUserResolver.class})
@ActiveProfiles("test")
class MeSpeedrunControllerTest {

    private static final String SPEEDRUN = "/api/me/speedrun";
    private static final String TOKEN = "catr_" + "t".repeat(43);
    private static final SpeedrunIdentity RUNNER = new SpeedrunIdentity("k1", "KeyOwner",
            "https://www.speedrun.com/users/KeyOwner", SpeedrunIdentity.Source.API_KEY);
    private static final SpeedrunRun PB = new SpeedrunRun("o1y9j9v6", "Celeste", "c1", "Any%", 42,
            new BigDecimal("1612.5"), LocalDate.of(2026, 9, 1), null, "https://www.speedrun.com/celeste/run/pb");
    private static final SpeedrunRun NEW = new SpeedrunRun("o1y9j9v6", "Celeste", "c1", "Any%", null,
            new BigDecimal("1600.0"), null, Instant.parse("2026-11-20T10:00:00Z"),
            "https://www.speedrun.com/celeste/run/new");

    @Autowired MockMvc mvc;
    @MockitoBean UserApiTokenService tokens;
    @MockitoBean UserAccountRepository accounts;
    @MockitoBean SpeedrunIdentityResolver resolver;
    @MockitoBean SpeedrunPersonalService personal;

    private UserAccount user;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(accounts.findById(user.getId())).thenReturn(Optional.of(user));
        when(tokens.authenticate(TOKEN)).thenReturn(Optional.of(new ApiTokenOwner(user, ApiTokenScope.READ)));
        when(personal.enabled()).thenReturn(true);
    }

    private static MockHttpServletRequestBuilder v3(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TOKEN).header("X-API-Version", "3");
    }

    @Test
    void identifiedAccount_allPersonalBestsAndPendingRuns() throws Exception {
        when(resolver.resolve(eq(user), any())).thenReturn(Optional.of(RUNNER));
        when(personal.personalBests(eq("k1"), isNull(), any())).thenReturn(Optional.of(List.of(PB)));
        when(personal.apiKey(user, RUNNER)).thenReturn(Optional.of("secret"));
        when(personal.pendingRuns(eq(RUNNER), isNull(), eq(Optional.of("secret")), any()))
                .thenReturn(Optional.of(List.of(NEW)));

        mvc.perform(v3(get(SPEEDRUN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.name").value("KeyOwner"))
                .andExpect(jsonPath("$.account.url").value("https://www.speedrun.com/users/KeyOwner"))
                .andExpect(jsonPath("$.account.source").value("api_key"))
                .andExpect(jsonPath("$.account.userId").doesNotExist())
                .andExpect(jsonPath("$.personalBests[0].game").value("Celeste"))
                .andExpect(jsonPath("$.personalBests[0].category").value("Any%"))
                .andExpect(jsonPath("$.personalBests[0].place").value(42))
                .andExpect(jsonPath("$.pendingRuns[0].submitted").value("2026-11-20T10:00:00Z"))
                .andExpect(jsonPath("$.pendingRuns[0].runUrl").value("https://www.speedrun.com/celeste/run/new"));
        verify(resolver).resolve(eq(user), eq(FetchBudget.UNLIMITED));
    }

    @Test
    void unknownPersonalData_isNull() throws Exception {
        when(resolver.resolve(eq(user), any())).thenReturn(Optional.of(RUNNER));
        when(personal.personalBests(any(), any(), any())).thenReturn(Optional.empty());
        when(personal.apiKey(any(), any())).thenReturn(Optional.empty());
        when(personal.pendingRuns(any(), any(), any(), any())).thenReturn(Optional.empty());

        mvc.perform(v3(get(SPEEDRUN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.name").value("KeyOwner"))
                .andExpect(jsonPath("$.personalBests").doesNotExist())
                .andExpect(jsonPath("$.pendingRuns").doesNotExist());
    }

    @Test
    void noAccountIs404() throws Exception {
        when(resolver.resolve(eq(user), any())).thenReturn(Optional.empty());

        mvc.perform(v3(get(SPEEDRUN)))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("not_found"));
        verify(personal, never()).personalBests(any(), any(), any());
    }

    @Test
    void sourceOffIs404() throws Exception {
        when(personal.enabled()).thenReturn(false);

        mvc.perform(v3(get(SPEEDRUN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        verify(resolver, never()).resolve(any(), any());
    }

    @Test
    void spentBudgetIs429() throws Exception {
        when(resolver.resolve(eq(user), any()))
                .thenThrow(new UpstreamBudgetExceededException(Duration.ofSeconds(30)));

        mvc.perform(v3(get(SPEEDRUN)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"));
    }

    @Test
    void withoutToken_401() throws Exception {
        mvc.perform(get(SPEEDRUN).header("X-API-Version", "3"))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class Keys {
        @Bean
        SecretKey jwtSecretKey() {
            return new SecretKeySpec(new byte[32], "HmacSHA256");
        }
    }
}
