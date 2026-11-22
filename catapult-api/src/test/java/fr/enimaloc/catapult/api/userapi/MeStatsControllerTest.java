package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.domain.account.ApiTokenScope;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.ApiSecurityConfig;
import fr.enimaloc.catapult.service.account.ApiTokenOwner;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.MemberFetchBudgets;
import fr.enimaloc.catapult.service.enrichment.rastats.RaAccountView;
import fr.enimaloc.catapult.service.enrichment.rastats.RetroAchievementsStatsService;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Achievement;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.AchievementSummary;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.PlatformStats;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import fr.enimaloc.catapult.service.enrichment.steamstats.SteamStatsService;
import fr.enimaloc.catapult.service.enrichment.xboxstats.XboxStatsService;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = MeStatsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({ApiSecurityConfig.class, MeStatsControllerTest.Keys.class, ApiUserResolver.class})
@ActiveProfiles("test")
class MeStatsControllerTest {

    private static final String STATS = "/api/me/stats";
    private static final String TOKEN = "catr_" + "t".repeat(43);
    private static final PlatformStats STEAM = new PlatformStats(Status.OK, "440", 7320L, 95L, null,
            AchievementSummary.of(List.of(new Achievement("A1", "First", "d", "i", true,
                    Instant.parse("2026-11-20T10:00:00Z"), new BigDecimal("3.2"), null, false))),
            List.of(new Achievement("A1", "First", "d", "i", true, Instant.parse("2026-11-20T10:00:00Z"),
                    new BigDecimal("3.2"), null, false)), Map.of());

    @Autowired MockMvc mvc;
    @MockitoBean UserApiTokenService tokens;
    @MockitoBean UserAccountRepository accounts;
    @MockitoBean GameInfoAssembler gameInfoAssembler;
    @MockitoBean SteamStatsService steamStats;
    @MockitoBean XboxStatsService xboxStats;
    @MockitoBean RetroAchievementsStatsService raStats;
    @MockitoBean MemberFetchBudgets budgets;

    private UserAccount user;
    private boolean budgetLeft = true;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(accounts.findById(user.getId())).thenReturn(Optional.of(user));
        when(tokens.authenticate(TOKEN)).thenReturn(Optional.of(new ApiTokenOwner(user, ApiTokenScope.READ)));
        when(budgets.forMember(user.getId())).thenReturn(new FetchBudget() {
            @Override
            public boolean tryAcquire() {
                return budgetLeft;
            }

            @Override
            public Duration retryAfter() {
                return Duration.ofSeconds(42);
            }
        });
    }

    private static MockHttpServletRequestBuilder v3(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TOKEN).header("X-API-Version", "3");
    }

    @Test
    void currentGameStats() throws Exception {
        GameInfoV3Response.StatsObject stats = new GameInfoV3Response.StatsObject(
                new GameInfoV3Response.SteamStats(Status.OK, "440", 7320L, 95L, null, null,
                        "http://localhost/api/me/stats/steam/440?version=3"), null, null, null);
        when(gameInfoAssembler.currentStats(user, Locale.FRENCH))
                .thenReturn(new MeStatsController.CurrentStats(true, stats));

        mvc.perform(v3(get(STATS)).param("lang", "fr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inGame").value(true))
                .andExpect(jsonPath("$.stats.steam.playtimeMinutes").value(7320))
                .andExpect(jsonPath("$.stats.steam.more").value("http://localhost/api/me/stats/steam/440?version=3"))
                .andExpect(jsonPath("$.stats.xbox").doesNotExist());
    }

    @Test
    void steamDetail_everyAchievement() throws Exception {
        when(steamStats.forGame(eq(user), eq("440"), eq(Locale.ENGLISH), any(), eq(true)))
                .thenReturn(Optional.of(STEAM));

        mvc.perform(v3(get(STATS + "/steam/440")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.id").value("440"))
                .andExpect(jsonPath("$.all[0].name").value("First"))
                .andExpect(jsonPath("$.all[0].rarity").value(3.2));
    }

    @Test
    void privateIs200WithStatus() throws Exception {
        when(steamStats.forGame(eq(user), eq("440"), any(), any(), eq(true)))
                .thenReturn(Optional.of(PlatformStats.of(Status.PRIVATE, "440")));
        when(xboxStats.forTitle(eq(user), eq("1144039928"), any(), eq(true)))
                .thenReturn(Optional.of(PlatformStats.of(Status.UNSUPPORTED, "1144039928")));

        mvc.perform(v3(get(STATS + "/steam/440")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("private"));
        mvc.perform(v3(get(STATS + "/xbox/1144039928")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unsupported"));
    }

    @Test
    void unlinkedPlatformIs404() throws Exception {
        when(steamStats.forGame(any(), any(), any(), any(), anyBoolean())).thenReturn(Optional.empty());
        when(xboxStats.forTitle(any(), any(), any(), anyBoolean())).thenReturn(Optional.empty());
        when(raStats.forGame(any(), anyInt(), any(), anyBoolean())).thenReturn(Optional.empty());
        when(raStats.account(any(), any())).thenReturn(Optional.empty());

        for (String path : List.of("/steam/440", "/xbox/1144039928", "/retroachievements/228", "/retroachievements")) {
            mvc.perform(v3(get(STATS + path)))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string("Content-Type", "application/problem+json"))
                    .andExpect(jsonPath("$.code").value("not_found"));
        }
    }

    @Test
    void malformedIdIs400() throws Exception {
        for (String path : List.of("/steam/abc", "/xbox/12345678901", "/retroachievements/1234567890")) {
            mvc.perform(v3(get(STATS + path)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        verifyNoInteractions(steamStats, xboxStats, raStats);
    }

    @Test
    void memberBudgetSpentIs429() throws Exception {
        budgetLeft = false;

        for (String path : List.of("/steam/440", "/xbox/1144039928", "/retroachievements/228")) {
            mvc.perform(v3(get(STATS + path)))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "42"))
                    .andExpect(jsonPath("$.code").value("too_many_requests"));
        }
        verifyNoInteractions(steamStats, xboxStats, raStats);
    }

    @Test
    void oneCallIsOneRefresh() throws Exception {
        when(raStats.forGame(eq(user), eq(228), any(), eq(true))).thenAnswer(invocation -> {
            FetchBudget budget = invocation.getArgument(2);
            budgetLeft = false;
            // already granted for this call: the upstream calls it makes are not charged again
            return budget.tryAcquire() && budget.tryAcquire()
                    ? Optional.of(PlatformStats.of(Status.OK, "228")) : Optional.empty();
        });

        mvc.perform(v3(get(STATS + "/retroachievements/228")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("228"));
    }

    @Test
    void retroAchievementsAccount() throws Exception {
        when(raStats.account(eq(user), eq(FetchBudget.UNLIMITED))).thenReturn(Optional.of(new RaAccountView(
                Status.OK, "Runner", "https://retroachievements.org/user/Runner", null, 1200, 30, 4000, "Playing",
                228, List.of(), List.of())));

        mvc.perform(v3(get(STATS + "/retroachievements")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").value("Runner"))
                .andExpect(jsonPath("$.totalPoints").value(1200))
                .andExpect(jsonPath("$.status").value("ok"));
        verify(budgets, never()).forMember(any());
    }

    @Test
    void withoutToken_401() throws Exception {
        mvc.perform(get(STATS + "/steam/440").header("X-API-Version", "3"))
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
