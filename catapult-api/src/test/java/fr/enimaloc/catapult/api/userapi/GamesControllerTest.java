package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.security.ApiSecurityConfig;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The public game lookups, with the real API security: no credential needed, from anywhere, any origin. */
@WebMvcTest(
        controllers = GamesController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({ApiSecurityConfig.class, GamesControllerTest.Keys.class})
@ActiveProfiles("test")
class GamesControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean UserApiTokenService tokens;
    @MockitoBean GameInfoAssembler games;

    /** A caller that is neither loopback nor authenticated. */
    private static MockHttpServletRequestBuilder remote(MockHttpServletRequestBuilder request) {
        return request.with(r -> {
            r.setRemoteAddr("203.0.113.7");
            return r;
        });
    }

    @Test
    void games_steam_isPublic() throws Exception {
        when(games.steam(eq("400"), any(Locale.class))).thenReturn(Optional.of(
                new SteamDetailResponse("https://store.steampowered.com/app/400", Set.of(), null, null, null)));

        mvc.perform(remote(get("/api/games/steam/400")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeUrl").value("https://store.steampowered.com/app/400"));
    }

    @Test
    void games_steam_lang_winsOverAcceptLanguage() throws Exception {
        mvc.perform(remote(get("/api/games/steam/400")).param("lang", "fr").header("Accept-Language", "de"));

        verify(games).steam("400", Locale.FRENCH);
    }

    @Test
    void games_unknown_is404Problem() throws Exception {
        when(games.igdb(anyString())).thenReturn(Optional.empty());
        when(games.steam(anyString(), any())).thenReturn(Optional.empty());
        when(games.xbox(anyString(), any())).thenReturn(Optional.empty());
        when(games.dtdd(anyLong())).thenReturn(Optional.empty());

        for (String path : new String[] {"/api/games/igdb/1", "/api/games/steam/1", "/api/games/xbox/X",
                "/api/games/dtdd/1"}) {
            mvc.perform(remote(get(path)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("not_found"));
        }
    }

    @Test
    void games_answerAnyOrigin() throws Exception {
        when(games.dtdd(16758L)).thenReturn(Optional.empty());

        mvc.perform(remote(get("/api/games/dtdd/16758")).header("Origin", "https://twitchat.fr"))
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
    }

    @Test
    void games_version2_isServed() throws Exception {
        when(games.igdb("1234")).thenReturn(Optional.empty());

        mvc.perform(remote(get("/api/games/igdb/1234")).header(ApiV2.VERSION_HEADER, ApiV2.VERSION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        verify(games).igdb("1234");
    }

    @TestConfiguration
    static class Keys {
        @Bean
        SecretKey jwtSecretKey() {
            return new SecretKeySpec(new byte[32], "HmacSHA256");
        }
    }
}
