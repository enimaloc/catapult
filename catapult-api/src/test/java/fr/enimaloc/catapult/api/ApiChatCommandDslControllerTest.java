package fr.enimaloc.catapult.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.chat.PlaceholderResolver;
import fr.enimaloc.catapult.chat.command.js.JsCompiler;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.domain.IgdbGameDetails;
import fr.enimaloc.catapult.service.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.IgdbService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiChatCommandDslController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
class ApiChatCommandDslControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ServiceFunctionRegistry serviceFunctionRegistry;
    @MockitoBean JsCompiler jsCompiler;
    @MockitoBean IgdbService igdbService;
    @MockitoBean IgdbGameDetailsService igdbGameDetailsService;
    @MockitoBean PlaceholderResolver placeholderResolver;
    final ObjectMapper om = new ObjectMapper();

    private static ServiceFunction fn(String namespace, String name, List<String> parameterNames) {
        return new ServiceFunction() {
            @Override public String namespace() { return namespace; }
            @Override public String name() { return name; }
            @Override public List<String> parameterNames() { return parameterNames; }
            @Override public Object invoke(Object[] args) { return null; }
        };
    }

    @Test
    void catalogReturnsKnownContextPathsAndRegisteredServiceFunctions() throws Exception {
        when(serviceFunctionRegistry.all()).thenReturn(List.of(
                fn("igdb", "getGame", List.of("query")),
                fn("twitch", "getUser", List.of())));

        mvc.perform(get("/api/chat-commands/dsl/catalog").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextPaths").isArray())
                .andExpect(jsonPath("$.contextPaths", org.hamcrest.Matchers.hasItem("game#name")))
                .andExpect(jsonPath("$.serviceFunctions", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.serviceFunctions[0].namespace").value("igdb"))
                .andExpect(jsonPath("$.serviceFunctions[0].name").value("getGame"))
                .andExpect(jsonPath("$.serviceFunctions[0].parameterNames[0]").value("query"))
                .andExpect(jsonPath("$.serviceFunctions[1].namespace").value("twitch"))
                .andExpect(jsonPath("$.serviceFunctions[1].parameterNames").isEmpty());
    }

    @Test
    void textToAstReturnsBadRequestNotAServerErrorForMalformedDslText() throws Exception {
        mvc.perform(post("/api/chat-commands/dsl/text-to-ast").with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("text", "{if x}no operator{/if}"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void textToAstReturnsAstForValidDslText() throws Exception {
        mvc.perform(post("/api/chat-commands/dsl/text-to-ast").with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("text", "{game#name}"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ast").value(org.hamcrest.Matchers.containsString("context-get")));
    }

    @Test
    void textToAstAcceptsLegacyDotSeparatedContextPathsInsteadOfFailing() throws Exception {
        // Pre-V59__placeholder_hash_separator.sql templates used '.' (e.g. {game.name});
        // the parser normalizes it rather than rejecting still-legacy-shaped commands.
        mvc.perform(post("/api/chat-commands/dsl/text-to-ast").with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("text", "{game.name}"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ast").value(org.hamcrest.Matchers.containsString("game#name")));
    }

    @Test
    void astToTextReturnsBadRequestNotAServerErrorForMalformedAstJson() throws Exception {
        mvc.perform(post("/api/chat-commands/dsl/ast-to-text").with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ast", "not json"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void astToJsReturnsTheCompiledJsForTheReadOnlyJsTab() throws Exception {
        when(jsCompiler.compile(org.mockito.ArgumentMatchers.any()))
                .thenReturn("let __output = \"\";\n__output += (\"hi\");\nreturn __output;\n");

        mvc.perform(post("/api/chat-commands/dsl/ast-to-js").with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ast", "{\"statements\":[]}"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.js").value(org.hamcrest.Matchers.containsString("__output")));
    }

    @Test
    void astToJsReturnsBadRequestNotAServerErrorForMalformedAstJson() throws Exception {
        mvc.perform(post("/api/chat-commands/dsl/ast-to-js").with(jwt()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ast", "not json"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void igdbSearchReturnsEmptyListForBlankQuery() throws Exception {
        mvc.perform(get("/api/chat-commands/dsl/igdb-search?q=").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void igdbSearchReturnsResultsFromIgdbService() throws Exception {
        when(igdbService.searchGames("zelda")).thenReturn(List.of(new IgdbService.IgdbGame("1", "Zelda")));

        mvc.perform(get("/api/chat-commands/dsl/igdb-search?q=zelda").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("1"))
                .andExpect(jsonPath("$[0].name").value("Zelda"));
    }

    @Test
    void igdbPreviewResolvesGamePlaceholdersViaPlaceholderResolverAndOmitsUnresolvedOnes() throws Exception {
        // Real formatting/URL-construction logic lives in PlaceholderResolver (tested there) —
        // this only verifies the controller wires a GameContext built from IgdbGameDetails
        // through it for every game# path and drops the ones that resolve to null
        // (game#agerating comes from a different subsystem, not plain IGDB details).
        IgdbGameDetails details = new IgdbGameDetails();
        details.setIgdbId("123");
        details.setSummary("A great game");
        details.setSlug("great-game");
        details.setWebsites(Map.of("steam", "https://store.steampowered.com/app/123"));
        when(igdbGameDetailsService.getDetails("123")).thenReturn(Optional.of(details));
        when(placeholderResolver.lookupRaw(any(), eq("game#name"), any())).thenReturn("Great Game");
        when(placeholderResolver.lookupRaw(any(), eq("game#summary"), any())).thenReturn("A great game");
        when(placeholderResolver.lookupRaw(any(), eq("game#store#steam"), any()))
                .thenReturn("https://store.steampowered.com/app/123");

        mvc.perform(get("/api/chat-commands/dsl/igdb-preview?id=123&name=Great Game").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['game#name']").value("Great Game"))
                .andExpect(jsonPath("$['game#summary']").value("A great game"))
                .andExpect(jsonPath("$['game#store#steam']").value("https://store.steampowered.com/app/123"))
                .andExpect(jsonPath("$['game#agerating']").doesNotExist());
    }
}
