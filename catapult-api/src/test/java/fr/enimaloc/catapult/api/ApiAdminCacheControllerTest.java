package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.chat.DynamicCommandResolver;
import fr.enimaloc.catapult.chat.TwPlaceholderRegistry;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.IgdbGameCcl;
import fr.enimaloc.catapult.domain.IgdbGameDetails;
import fr.enimaloc.catapult.domain.TwDefinition;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.IgdbGameCclRepository;
import fr.enimaloc.catapult.repository.IgdbGameDetailsRepository;
import fr.enimaloc.catapult.repository.TwDefinitionRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.IgdbService.IgdbGame;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    @Autowired WebApplicationContext wac;
    @MockitoBean IgdbService igdbService;
    @MockitoBean DynamicCommandResolver dynamicCommandResolver;
    @MockitoBean TwPlaceholderRegistry twPlaceholderRegistry;
    @MockitoBean IgdbGameDetailsRepository igdbGameDetailsRepository;
    @MockitoBean IgdbGameCclRepository igdbGameCclRepository;
    @MockitoBean TwDefinitionRepository twDefinitionRepository;
    @MockitoBean ChatCommandDefinitionRepository chatCommandDefinitionRepository;
    @MockitoBean UserAccountRepository userAccountRepository;

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

    @Test
    void entryDetail_gameCache_resolvesIgdbGameDetails() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        IgdbGameDetails details = new IgdbGameDetails();
        details.setIgdbId("103");
        details.setSlug("portal");
        details.setSummary("A puzzle game.");
        details.setFetchedAt(Instant.now());
        when(igdbGameDetailsRepository.findById("103")).thenReturn(Optional.of(details));

        mvc.perform(get("/api/admin/caches/igdb-game-cache/entry").param("key", "103"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("103"))
                .andExpect(jsonPath("$.detail.slug").value("portal"))
                .andExpect(jsonPath("$.expiresInSeconds").exists());
    }

    @Test
    void entryDetail_gameCache_missingDbRowReturnsNullDetail() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        when(igdbGameDetailsRepository.findById("999")).thenReturn(Optional.empty());

        mvc.perform(get("/api/admin/caches/igdb-game-cache/entry").param("key", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("999"))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    void entryDetail_nameIndex_resolvesViaEmbeddedIgdbId() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        when(igdbService.getNameIndex()).thenReturn(Map.of("portal 2", new IgdbGame("104", "Portal 2")));
        IgdbGameDetails details = new IgdbGameDetails();
        details.setIgdbId("104");
        when(igdbGameDetailsRepository.findById("104")).thenReturn(Optional.of(details));

        mvc.perform(get("/api/admin/caches/igdb-name-index/entry").param("key", "portal 2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.igdbId").value("104"));
    }

    @Test
    void entryDetail_cclCache_resolvesIgdbGameCcl() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        IgdbGameCcl ccl = new IgdbGameCcl("103", Set.of("ViolentGraphic"), "PEGI 16");
        when(igdbGameCclRepository.findById("103")).thenReturn(Optional.of(ccl));

        mvc.perform(get("/api/admin/caches/igdb-ccl-cache/entry").param("key", "103"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.ageRatings").value("PEGI 16"))
                .andExpect(jsonPath("$.expiresInSeconds").doesNotExist());
    }

    @Test
    void entryDetail_twAllOptions_resolvesTwDefinition() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        TwDefinition def = new TwDefinition();
        def.setId("flashing-lights");
        def.setLabel("Flashing lights");
        def.setDescription("Strobe or rapid flashing.");
        when(twDefinitionRepository.findById("flashing-lights")).thenReturn(Optional.of(def));

        mvc.perform(get("/api/admin/caches/tw-all-options/entry").param("key", "flashing-lights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.description").value("Strobe or rapid flashing."));
    }

    @Test
    void entryDetail_chatCommandUserCache_resolvesAllDefinitionsForUser() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setId(UUID.randomUUID());
        def.setName("hello");
        def.setTemplate("Hi there!");
        when(chatCommandDefinitionRepository.findByUser(user)).thenReturn(List.of(def));

        mvc.perform(get("/api/admin/caches/chat-command-user-cache/entry").param("key", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail[0].name").value("hello"));
    }
}
