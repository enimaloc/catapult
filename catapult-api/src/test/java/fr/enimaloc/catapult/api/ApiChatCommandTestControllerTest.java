package fr.enimaloc.catapult.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import fr.enimaloc.catapult.chat.command.js.JsCompiler;
import fr.enimaloc.catapult.chat.command.js.SandboxExecutor;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.ExperimentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiChatCommandTestController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
class ApiChatCommandTestControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();
    @MockitoBean ChatCommandDefinitionRepository repository;
    @MockitoBean UserAccountRepository userAccountRepository;
    @MockitoBean ExperimentService experimentService;
    @MockitoBean JsCompiler jsCompiler;
    @MockitoBean SandboxExecutor sandboxExecutor;
    @MockitoBean ServiceFunctionRegistry serviceFunctionRegistry;

    private UserAccount mockUser() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("99");
        user.setTwitchUsername("streamer");
        when(userAccountRepository.findByTwitchId(any())).thenReturn(Optional.of(user));
        return user;
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withAdmin(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rb) {
        return rb
                .with(jwt().jwt(j -> j.claim("twitchId", "99"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .with(csrf());
    }

    @Test
    void test_returns_403_when_not_rolled_out() throws Exception {
        mockUser();
        when(experimentService.evaluateGate(any(), eq("chat.commands"))).thenReturn(false);

        mvc.perform(withAdmin(post("/api/chat-commands/{id}/test", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("overrides", Map.of()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void test_returns_404_for_a_command_owned_by_another_user() throws Exception {
        mockUser();
        when(experimentService.evaluateGate(any(), eq("chat.commands"))).thenReturn(true);

        UUID otherId = UUID.randomUUID();
        UserAccount otherOwner = new UserAccount();
        otherOwner.setId(UUID.randomUUID());
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setId(otherId);
        def.setUser(otherOwner);
        def.setName("!foo");
        def.setTemplate("Hi");
        def.setPermission(ChatCommandEvent.SenderRole.EVERYONE);
        when(repository.findById(otherId)).thenReturn(Optional.of(def));

        mvc.perform(withAdmin(post("/api/chat-commands/{id}/test", otherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("overrides", Map.of()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void test_runs_the_sandbox_for_the_caller_own_command() throws Exception {
        UserAccount user = mockUser();
        when(experimentService.evaluateGate(any(), eq("chat.commands"))).thenReturn(true);

        UUID id = UUID.randomUUID();
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setId(id);
        def.setUser(user);
        def.setName("!foo");
        def.setTemplate("Now playing {game#name}!");
        def.setAst(new NodeJsonCodec().toJson(new CommandDslParser().parse("Now playing {game#name}!")));
        def.setPermission(ChatCommandEvent.SenderRole.EVERYONE);
        when(repository.findById(id)).thenReturn(Optional.of(def));
        when(jsCompiler.compileWithTrace(any())).thenReturn("return \"Now playing Valorant!\";");
        var trace = new fr.enimaloc.catapult.chat.command.trace.ExecutionTrace();
        trace.finish("Now playing Valorant!");
        when(sandboxExecutor.executeWithTrace(any(), any(), any(), any(), any())).thenReturn(trace);

        mvc.perform(withAdmin(post("/api/chat-commands/{id}/test", id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("overrides", Map.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output").value("Now playing Valorant!"));

        org.mockito.Mockito.verify(jsCompiler).compileWithTrace(any());
        org.mockito.Mockito.verify(jsCompiler, org.mockito.Mockito.never()).compile(any());
    }
}
