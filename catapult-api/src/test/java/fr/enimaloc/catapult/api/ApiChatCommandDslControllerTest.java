package fr.enimaloc.catapult.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiChatCommandDslController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
class ApiChatCommandDslControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();

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
}
