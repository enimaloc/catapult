package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.chat.DynamicCommandResolver;
import fr.enimaloc.catapult.chat.TwPlaceholderRegistry;
import fr.enimaloc.catapult.service.IgdbService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiAdminCacheController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
class ApiAdminCacheControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean IgdbService igdbService;
    @MockitoBean DynamicCommandResolver dynamicCommandResolver;
    @MockitoBean TwPlaceholderRegistry twPlaceholderRegistry;

    private Map<String, String> fiveGameEntries() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("101", "Half-Life");
        map.put("102", "Half-Life 2");
        map.put("103", "Portal");
        map.put("104", "Portal 2");
        map.put("105", "Team Fortress 2");
        return map;
    }

    @Test
    void entries_defaultPaginationReturnsFirstPage() throws Exception {
        when(igdbService.getGameCache()).thenReturn(fiveGameEntries());

        mvc.perform(get("/api/admin/caches/igdb-game-cache").param("size", "2")
                .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void entries_secondPageReturnsRemainder() throws Exception {
        when(igdbService.getGameCache()).thenReturn(fiveGameEntries());

        mvc.perform(get("/api/admin/caches/igdb-game-cache").param("size", "2").param("page", "2")
                .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].key").value("105"));
    }

    @Test
    void entries_searchMatchesKey() throws Exception {
        when(igdbService.getGameCache()).thenReturn(fiveGameEntries());

        mvc.perform(get("/api/admin/caches/igdb-game-cache").param("q", "103")
                .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].value").value("Portal"));
    }

    @Test
    void entries_searchMatchesValueCaseInsensitive() throws Exception {
        when(igdbService.getGameCache()).thenReturn(fiveGameEntries());

        mvc.perform(get("/api/admin/caches/igdb-game-cache").param("q", "PORTAL")
                .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void entries_searchWithNoMatchReturnsEmptyPage() throws Exception {
        when(igdbService.getGameCache()).thenReturn(fiveGameEntries());

        mvc.perform(get("/api/admin/caches/igdb-game-cache").param("q", "nonexistent")
                .with(jwt().jwt(j -> j.claim("twitchId", "123")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
    }
}
