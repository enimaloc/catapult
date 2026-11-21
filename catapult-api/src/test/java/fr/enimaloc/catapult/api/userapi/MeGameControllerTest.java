package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.domain.account.ApiTokenScope;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.ApiSecurityConfig;
import fr.enimaloc.catapult.service.account.ApiTokenOwner;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.enrichment.GameEnrichment;
import fr.enimaloc.catapult.service.enrichment.hltb.TimeToBeat;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbSummary;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckCompatibility;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = MeGameController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({ApiSecurityConfig.class, MeGameControllerTest.Keys.class, ApiUserResolver.class})
@ActiveProfiles("test")
class MeGameControllerTest {

    private static final String TOKEN = "cat_" + "t".repeat(43);

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
        when(tokens.authenticate(TOKEN)).thenReturn(Optional.of(new ApiTokenOwner(user, ApiTokenScope.FULL)));
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TOKEN);
    }

    private void playingHalo() {
        GameInfoResponse.XboxObject xbox = new GameInfoResponse.XboxObject("9NBLGGH2JHXJ", "Halo", "Halo desc",
                "Xbox Game Studios", "343", "https://cover", "https://store/halo", "http://localhost/more");
        when(games.gameInfo(eq(user), any(Locale.class)))
                .thenReturn(new GameInfoResponse(true, null, null, xbox, null, null, "Halo"));
    }

    @Test
    void meGame_returnsTheTokenOwnersGame() throws Exception {
        playingHalo();

        mvc.perform(withToken(get("/api/me/game")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inGame").value(true))
                .andExpect(jsonPath("$.xbox.productId").value("9NBLGGH2JHXJ"))
                .andExpect(jsonPath("$.name").value("Halo"));

        verify(games).gameInfo(user, Locale.ENGLISH);
    }

    @Test
    void meGame_nothingDetected_isNotInGame() throws Exception {
        when(games.gameInfo(eq(user), any(Locale.class))).thenReturn(new GameInfoResponse(false));

        mvc.perform(withToken(get("/api/me/game")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inGame").value(false));
    }

    @Test
    void meGame_lang_winsOverAcceptLanguage() throws Exception {
        playingHalo();

        mvc.perform(withToken(get("/api/me/game")).param("lang", "fr").header("Accept-Language", "de-DE"))
                .andExpect(status().isOk());
        verify(games).gameInfo(user, Locale.FRENCH);

        mvc.perform(withToken(get("/api/me/game")).header("Accept-Language", "de-DE,de;q=0.9"))
                .andExpect(status().isOk());
        verify(games).gameInfo(user, Locale.forLanguageTag("de-DE"));
    }

    @Test
    void meGame_field_returnsPlainText() throws Exception {
        playingHalo();

        mvc.perform(withToken(get("/api/me/game")).param("f", "xbox.publisherName"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
                .andExpect(content().string("Xbox Game Studios"));
    }

    @Test
    void meGameSummary_keepsTheSummaryFieldsOnly() throws Exception {
        playingHalo();

        mvc.perform(withToken(get("/api/me/game/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inGame").value(true))
                .andExpect(jsonPath("$.name").value("Halo"))
                .andExpect(jsonPath("$.xbox").doesNotExist());
    }

    @Test
    void meGameSummary_field_returnsPlainText() throws Exception {
        playingHalo();

        mvc.perform(withToken(get("/api/me/game/summary")).param("f", "name"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
                .andExpect(content().string("Halo"));
        // Outside the summary view: empty, as on the legacy summary route
        mvc.perform(withToken(get("/api/me/game/summary")).param("f", "xbox.name"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    private void playingPortalV3() {
        GameInfoResponse.XboxObject xbox = new GameInfoResponse.XboxObject("9NBLGGH2JHXJ", "Portal", "Portal desc",
                "Valve", "Valve", "https://cover", "https://store/portal", "http://localhost/more");
        GameInfoResponse base = new GameInfoResponse(true, null, null, xbox, null, null, "Portal");
        GameEnrichment enrichment = new GameEnrichment(new TimeToBeat(3600L, 7200L, 10800L, 42),
                new ProtonDbSummary("platinum", "gold", "platinum", "strong", 0.9, 120,
                        "https://www.protondb.com/app/400"),
                null, new SteamDeckCompatibility(SteamDeckCompatibility.Category.VERIFIED, List.of()), null,
                new GameEnrichment.Trailer(GameEnrichment.Trailer.YOUTUBE, "https://www.youtube.com/watch?v=x"),
                "400");
        when(games.gameInfoV3(eq(user), any(Locale.class))).thenReturn(GameInfoV3Response.of(base, enrichment,
                new MoreLinks("http://localhost", GameInfoAssembler.LinkStyle.CURRENT), Optional.of("1942")));
    }

    @Test
    void meGame_v3_hasEnrichmentObjects() throws Exception {
        playingPortalV3();

        mvc.perform(withToken(get("/api/me/game")).header(ApiV2.VERSION_HEADER, "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inGame").value(true))
                .andExpect(jsonPath("$.xbox.productId").value("9NBLGGH2JHXJ"))
                .andExpect(jsonPath("$.hltb.normally").value(7200))
                .andExpect(jsonPath("$.hltb.more").value("http://localhost/api/games/hltb/1942?version=3"))
                .andExpect(jsonPath("$.protondb.tier").value("platinum"))
                .andExpect(jsonPath("$.steamDeck.category").value("verified"))
                .andExpect(jsonPath("$.name").value("Portal"));

        verify(games).gameInfoV3(user, Locale.ENGLISH);
        verify(games, never()).gameInfo(any(UserAccount.class), any());
    }

    @Test
    void meGame_v3_field_returnsPlainText() throws Exception {
        playingPortalV3();

        mvc.perform(withToken(get("/api/me/game")).queryParam("version", "3").param("f", "protondb.tier"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
                .andExpect(content().string("platinum"));
    }

    @Test
    void meGame_v2_unchanged() throws Exception {
        playingHalo();

        mvc.perform(withToken(get("/api/me/game")).header(ApiV2.VERSION_HEADER, "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Halo"))
                .andExpect(jsonPath("$.hltb").doesNotExist())
                .andExpect(jsonPath("$.protondb").doesNotExist());

        verify(games, never()).gameInfoV3(any(UserAccount.class), any());
    }

    @Test
    void meGameSummary_v3_hasTimeToBeatAndTrailer() throws Exception {
        playingPortalV3();

        mvc.perform(withToken(get("/api/me/game/summary")).header(ApiV2.VERSION_HEADER, "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inGame").value(true))
                .andExpect(jsonPath("$.name").value("Portal"))
                .andExpect(jsonPath("$.timeToBeat").value(7200))
                .andExpect(jsonPath("$.protonTier").value("platinum"))
                .andExpect(jsonPath("$.deckStatus").value("verified"))
                .andExpect(jsonPath("$.trailer.kind").value("youtube"))
                .andExpect(jsonPath("$.trailer.url").value("https://www.youtube.com/watch?v=x"))
                .andExpect(jsonPath("$.hltb").doesNotExist())
                .andExpect(jsonPath("$.xbox").doesNotExist());
    }

    @Test
    void meGameSummary_v3_field_returnsPlainText() throws Exception {
        playingPortalV3();

        mvc.perform(withToken(get("/api/me/game/summary")).header(ApiV2.VERSION_HEADER, "3").param("f", "timeToBeat"))
                .andExpect(status().isOk())
                .andExpect(content().string("7200"));
        // Outside the summary view: empty
        mvc.perform(withToken(get("/api/me/game/summary")).header(ApiV2.VERSION_HEADER, "3").param("f", "hltb.count"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void meGameSummary_v2_hasNoEnrichmentField() throws Exception {
        playingHalo();

        mvc.perform(withToken(get("/api/me/game/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeToBeat").doesNotExist())
                .andExpect(jsonPath("$.trailer").doesNotExist());
    }

    @Test
    void aTokenWhoseAccountIsGone_is401() throws Exception {
        when(accounts.findById(user.getId())).thenReturn(Optional.empty());

        mvc.perform(withToken(get("/api/me/game"))).andExpect(status().isUnauthorized());
        verify(games, never()).gameInfo(any(UserAccount.class), any());
    }

    @TestConfiguration
    static class Keys {
        @Bean
        SecretKey jwtSecretKey() {
            return new SecretKeySpec(new byte[32], "HmacSHA256");
        }
    }
}
