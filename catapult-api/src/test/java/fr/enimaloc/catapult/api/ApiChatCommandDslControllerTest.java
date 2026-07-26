package fr.enimaloc.catapult.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.chat.PlaceholderResolver;
import fr.enimaloc.catapult.chat.command.js.JsCompiler;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.domain.ChatCommandSetting;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandSettingRepository;
import fr.enimaloc.catapult.repository.TwDefinitionRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
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
    @MockitoBean PlaceholderResolver placeholderResolver;
    @MockitoBean ChatCommandSettingRepository settingRepository;
    @MockitoBean UserAccountRepository userAccountRepository;
    @MockitoBean TwDefinitionRepository twDefinitionRepository;
    final ObjectMapper om = new ObjectMapper();

    private static ServiceFunction fn(String namespace, String name, List<String> parameterNames) {
        return new ServiceFunction() {
            @Override public String namespace() { return namespace; }
            @Override public String name() { return name; }
            @Override public List<String> parameterNames() { return parameterNames; }
            @Override public Object invoke(UserAccount user, Object[] args) { return null; }
        };
    }

    @Test
    void catalogReturnsKnownContextPathsAndRegisteredServiceFunctions() throws Exception {
        when(serviceFunctionRegistry.all()).thenReturn(List.of(
                fn("igdb", "getGame", List.of("query")),
                fn("twitch", "getUser", List.of())));
        when(twDefinitionRepository.findAllByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());

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
    void catalogReturnsTheCallersOwnSettingKeysSorted() throws Exception {
        UserAccount user = new UserAccount();
        when(userAccountRepository.findByTwitchId("99")).thenReturn(Optional.of(user));
        ChatCommandSetting region = new ChatCommandSetting();
        region.setKey("region");
        ChatCommandSetting language = new ChatCommandSetting();
        language.setKey("language");
        when(settingRepository.findByUser(user)).thenReturn(List.of(region, language));
        when(twDefinitionRepository.findAllByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());

        mvc.perform(get("/api/chat-commands/dsl/catalog")
                        .with(jwt().jwt(j -> j.claim("twitchId", "99"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settingKeys[0]").value("language"))
                .andExpect(jsonPath("$.settingKeys[1]").value("region"));
    }

    @Test
    void catalogReturnsEmptySettingKeysWhenTheJwtDoesNotMatchAnAccount() throws Exception {
        when(twDefinitionRepository.findAllByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());

        mvc.perform(get("/api/chat-commands/dsl/catalog").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settingKeys").isEmpty());
    }

    @Test
    void catalogReturnsKnownTwsFromTheEnabledDefinitions() throws Exception {
        fr.enimaloc.catapult.domain.TwDefinition def = new fr.enimaloc.catapult.domain.TwDefinition();
        def.setId("violence_graphic");
        def.setLabel("Violence (graphic)");
        when(twDefinitionRepository.findAllByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(def));

        mvc.perform(get("/api/chat-commands/dsl/catalog").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.knownTws[0].id").value("violence_graphic"))
                .andExpect(jsonPath("$.knownTws[0].label").value("Violence (graphic)"));
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
}
