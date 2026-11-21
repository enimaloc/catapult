package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.security.ApiSecurityConfig;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.IpFetchBudgets;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import fr.enimaloc.catapult.service.enrichment.hltb.HltbService;
import fr.enimaloc.catapult.service.enrichment.hltb.TimeToBeat;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiService;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbService;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbSummary;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckCompatibility;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckService;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbAssets;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbService;
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
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public per-source enrichment details, in API version 3 only, with the real API security and the real per-IP
 * budget (10 upstream calls a minute): the services stand for the cache, spending the budget on a miss only.
 */
@WebMvcTest(
        controllers = EnrichmentGamesController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({ApiSecurityConfig.class, EnrichmentGamesControllerTest.Keys.class, IpFetchBudgets.class,
        EnrichmentProperties.class})
@ActiveProfiles("test")
class EnrichmentGamesControllerTest {

    private static final ProtonDbSummary PORTAL = new ProtonDbSummary("platinum", "gold", "platinum", "strong",
            0.9, 120, "https://www.protondb.com/app/400");

    @Autowired MockMvc mvc;
    @MockitoBean UserApiTokenService tokens;
    @MockitoBean HltbService hltb;
    @MockitoBean ProtonDbService protonDb;
    @MockitoBean SteamDeckService steamDeck;
    @MockitoBean SteamGridDbService steamGridDb;
    @MockitoBean PcGamingWikiService pcGamingWiki;

    /** An anonymous caller from {@code ip}, asking for version 3. */
    private static MockHttpServletRequestBuilder v3(MockHttpServletRequestBuilder request, String ip) {
        return request.header(ApiV2.VERSION_HEADER, "3").with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    /** ProtonDB as the cache answers it: "400" is cached, any other app costs an upstream call. */
    private void protonDbCachingPortal() {
        when(protonDb.summary(anyString(), any(FetchBudget.class))).thenAnswer(call -> {
            String appId = call.getArgument(0);
            FetchBudget budget = call.getArgument(1);
            if ("400".equals(appId)) {
                return Optional.of(PORTAL);
            }
            if (!budget.tryAcquire()) {
                throw new UpstreamBudgetExceededException(budget.retryAfter());
            }
            return Optional.of(PORTAL);
        });
    }

    @Test
    void protondb_v3_returnsDetail() throws Exception {
        protonDbCachingPortal();

        mvc.perform(v3(get("/api/games/protondb/400"), "203.0.113.7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("platinum"))
                .andExpect(jsonPath("$.total").value(120))
                .andExpect(jsonPath("$.url").value("https://www.protondb.com/app/400"));
    }

    @Test
    void protondb_v2_notFound() throws Exception {
        protonDbCachingPortal();

        mvc.perform(get("/api/games/protondb/400").header(ApiV2.VERSION_HEADER, "2").with(r -> {
                    r.setRemoteAddr("203.0.113.7");
                    return r;
                }))
                .andExpect(status().isNotFound());
    }

    @Test
    void protondb_isOpenToAnyOrigin() throws Exception {
        mvc.perform(options("/api/games/protondb/400").header("Origin", "https://example.com")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", ApiV2.VERSION_HEADER))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
    }

    @Test
    void disabledSource_404() throws Exception {
        when(protonDb.summary(anyString(), any(FetchBudget.class))).thenReturn(Optional.empty());
        when(hltb.byIgdbId(anyString(), any(FetchBudget.class))).thenReturn(Optional.empty());
        when(steamDeck.compatibility(anyString(), any(FetchBudget.class))).thenReturn(Optional.empty());
        when(steamGridDb.byGameId(anyLong(), any(FetchBudget.class))).thenReturn(Optional.empty());
        when(pcGamingWiki.byPageId(anyLong(), any(FetchBudget.class))).thenReturn(Optional.empty());

        for (String path : List.of("hltb/1", "protondb/400", "steamdeck/400", "steamgriddb/1", "pcgamingwiki/1")) {
            mvc.perform(v3(get("/api/games/" + path), "203.0.113.7")).andExpect(status().isNotFound());
        }
    }

    /** A malformed id is refused before any cache read, upstream call or database write. */
    @Test
    void malformedIds_400_withoutTouchingTheSource() throws Exception {
        for (String path : List.of("protondb/abc", "protondb/" + "9".repeat(600), "protondb/-1",
                "steamdeck/abc", "steamdeck/12345678901", "hltb/abc", "hltb/" + "1".repeat(20),
                "steamgriddb/abc", "steamgriddb/0", "pcgamingwiki/abc", "pcgamingwiki/-3")) {
            mvc.perform(v3(get("/api/games/" + path), "203.0.113.7"))
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(protonDb, steamDeck, hltb, steamGridDb, pcGamingWiki);
    }

    @Test
    void budgetExhausted_429WithRetryAfter() throws Exception {
        protonDbCachingPortal();

        for (int i = 0; i < 10; i++) {
            mvc.perform(v3(get("/api/games/protondb/" + (1000 + i)), "198.51.100.1")).andExpect(status().isOk());
        }
        mvc.perform(v3(get("/api/games/protondb/2000"), "198.51.100.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        // Another client still has its whole budget
        mvc.perform(v3(get("/api/games/protondb/2000"), "198.51.100.2")).andExpect(status().isOk());
    }

    @Test
    void cachedHits_doNotConsumeBudget() throws Exception {
        protonDbCachingPortal();

        for (int i = 0; i < 20; i++) {
            mvc.perform(v3(get("/api/games/protondb/400"), "198.51.100.3")).andExpect(status().isOk());
        }
        mvc.perform(v3(get("/api/games/protondb/3000"), "198.51.100.3")).andExpect(status().isOk());
    }

    @Test
    void everySource_servesItsDetail() throws Exception {
        when(hltb.byIgdbId(eq("1942"), any(FetchBudget.class)))
                .thenReturn(Optional.of(new TimeToBeat(3600L, 7200L, null, 12)));
        when(steamDeck.compatibility(eq("400"), any(FetchBudget.class))).thenReturn(Optional.of(
                new SteamDeckCompatibility(SteamDeckCompatibility.Category.VERIFIED,
                        List.of(new SteamDeckCompatibility.TestedItem("4", "#SteamDeckVerified_TestResult_X")))));
        SteamGridDbAssets.Asset grid = new SteamGridDbAssets.Asset("https://grid", "https://thumb", 600, 900,
                "alternate");
        when(steamGridDb.byGameId(eq(5L), any(FetchBudget.class))).thenReturn(Optional.of(new SteamGridDbAssets(5L,
                "Portal", "https://www.steamgriddb.com/game/5", List.of(grid), List.of(), List.of(), List.of())));
        when(pcGamingWiki.byPageId(eq(77L), any(FetchBudget.class))).thenReturn(Optional.empty());

        mvc.perform(v3(get("/api/games/hltb/1942"), "203.0.113.7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.normally").value(7200))
                .andExpect(jsonPath("$.count").value(12));
        mvc.perform(v3(get("/api/games/steamdeck/400"), "203.0.113.7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("VERIFIED"))
                .andExpect(jsonPath("$.items[0].token").value("#SteamDeckVerified_TestResult_X"));
        mvc.perform(v3(get("/api/games/steamgriddb/5"), "203.0.113.7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grids[0].url").value("https://grid"));
        mvc.perform(v3(get("/api/games/pcgamingwiki/77"), "203.0.113.7"))
                .andExpect(status().isNotFound());
    }

    @TestConfiguration
    static class Keys {
        @Bean
        SecretKey jwtSecretKey() {
            return new SecretKeySpec(new byte[32], "HmacSHA256");
        }
    }
}
