package fr.enimaloc.catapult.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import fr.enimaloc.catapult.chat.command.js.JsCompiler;
import fr.enimaloc.catapult.chat.command.js.SandboxExecutor;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.ChatCommandParam;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.ChatCommandParamRepository;
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

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
    @MockitoBean ChatCommandParamRepository paramRepository;

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
        when(sandboxExecutor.executeWithTrace(any(), any(), any(), any(), any(), any(), any())).thenReturn(trace);

        mvc.perform(withAdmin(post("/api/chat-commands/{id}/test", id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("overrides", Map.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output").value("Now playing Valorant!"));

        org.mockito.Mockito.verify(jsCompiler).compileWithTrace(any());
        org.mockito.Mockito.verify(jsCompiler, org.mockito.Mockito.never()).compile(any());
    }

    @Test
    void test_runs_the_in_progress_unsaved_ejectedJs_from_the_request_body_over_the_ast() throws Exception {
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
        var trace = new fr.enimaloc.catapult.chat.command.trace.ExecutionTrace();
        trace.finish("hand-written output");
        when(sandboxExecutor.executeWithTrace(any(), any(), any(), any(), any(), any(), any())).thenReturn(trace);

        mvc.perform(withAdmin(post("/api/chat-commands/{id}/test", id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("overrides", Map.of(),
                                "ejectedJs", "return \"hand-written output\";"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output").value("hand-written output"));

        org.mockito.Mockito.verify(jsCompiler, org.mockito.Mockito.never()).compileWithTrace(any());
        org.mockito.Mockito.verify(sandboxExecutor).executeWithTrace(
                eq("return \"hand-written output\";"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void test_runs_the_persisted_ejectedJs_when_no_in_progress_override_is_sent() throws Exception {
        UserAccount user = mockUser();
        when(experimentService.evaluateGate(any(), eq("chat.commands"))).thenReturn(true);

        UUID id = UUID.randomUUID();
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setId(id);
        def.setUser(user);
        def.setName("!foo");
        def.setTemplate("this should never compile");
        def.setAst(new NodeJsonCodec().toJson(new CommandDslParser().parse("this should never compile")));
        def.setEjectedJs("return \"persisted ejected output\";");
        def.setPermission(ChatCommandEvent.SenderRole.EVERYONE);
        when(repository.findById(id)).thenReturn(Optional.of(def));
        var trace = new fr.enimaloc.catapult.chat.command.trace.ExecutionTrace();
        trace.finish("persisted ejected output");
        when(sandboxExecutor.executeWithTrace(any(), any(), any(), any(), any(), any(), any())).thenReturn(trace);

        mvc.perform(withAdmin(post("/api/chat-commands/{id}/test", id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("overrides", Map.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output").value("persisted ejected output"));

        org.mockito.Mockito.verify(jsCompiler, org.mockito.Mockito.never()).compileWithTrace(any());
        org.mockito.Mockito.verify(sandboxExecutor).executeWithTrace(
                eq("return \"persisted ejected output\";"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void test_resolves_a_placeholder_without_a_test_override_to_empty_string_not_java_null() throws Exception {
        // The real dispatch path (DynamicChatCommand#resolvePlaceholder) always falls back to
        // "" for a placeholder with no value — the Tester endpoint must match that, or GraalJS
        // would concatenate the literal string "null" for any override the caller left blank.
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
        when(jsCompiler.compileWithTrace(any())).thenReturn("return \"irrelevant\";");
        var trace = new fr.enimaloc.catapult.chat.command.trace.ExecutionTrace();
        trace.finish("irrelevant");
        when(sandboxExecutor.executeWithTrace(any(), any(), any(), any(), any(), any(), any())).thenReturn(trace);

        mvc.perform(withAdmin(post("/api/chat-commands/{id}/test", id))
                        .contentType(MediaType.APPLICATION_JSON)
                        // "tw#active" left out entirely, like a blank test-override field would be
                        .content(om.writeValueAsString(Map.of("overrides", Map.of("game#name", "Valorant")))))
                .andExpect(status().isOk());

        ArgumentCaptor<SandboxExecutor.PlaceholderContext> placeholderCaptor =
                ArgumentCaptor.forClass(SandboxExecutor.PlaceholderContext.class);
        verify(sandboxExecutor).executeWithTrace(any(), placeholderCaptor.capture(), any(), any(), any(), any(), any());

        assertThat(placeholderCaptor.getValue().resolve("game#name")).isEqualTo("Valorant");
        assertThat(placeholderCaptor.getValue().resolve("tw#active")).isEqualTo("");
    }

    @Test
    void test_resolves_ctx_params_from_the_real_saved_param_by_default() throws Exception {
        // Unlike context placeholders (no live game during a test), params are real persisted
        // per-streamer settings, so the Tester must reflect what's actually saved without the
        // caller needing to pass a "params" override for every key — this was a real bug: the
        // Tester used to ONLY look at a request-body "params" map, ignoring saved values entirely.
        UserAccount user = mockUser();
        when(experimentService.evaluateGate(any(), eq("chat.commands"))).thenReturn(true);

        UUID id = UUID.randomUUID();
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setId(id);
        def.setUser(user);
        def.setName("!lang");
        def.setTemplate("{ctx.params.language}");
        def.setAst(new NodeJsonCodec().toJson(new CommandDslParser().parse("{ctx.params.language}")));
        def.setPermission(ChatCommandEvent.SenderRole.EVERYONE);
        when(repository.findById(id)).thenReturn(Optional.of(def));
        ChatCommandParam savedParam = new ChatCommandParam();
        savedParam.setKey("language");
        savedParam.setValue("fr");
        when(paramRepository.findByUser(user)).thenReturn(java.util.List.of(savedParam));
        when(jsCompiler.compileWithTrace(any())).thenReturn("return ctx.params.language;");
        var trace = new fr.enimaloc.catapult.chat.command.trace.ExecutionTrace();
        trace.finish("fr");
        when(sandboxExecutor.executeWithTrace(any(), any(), any(), any(), any(), any(), any())).thenReturn(trace);

        // No "params" field sent at all — must still resolve from the real saved value.
        mvc.perform(withAdmin(post("/api/chat-commands/{id}/test", id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("overrides", Map.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output").value("fr"));

        ArgumentCaptor<SandboxExecutor.ParamContext> paramCaptor =
                ArgumentCaptor.forClass(SandboxExecutor.ParamContext.class);
        verify(sandboxExecutor).executeWithTrace(any(), any(), any(), any(), any(), paramCaptor.capture(), any());
        assertThat(paramCaptor.getValue().resolve("language")).isEqualTo("fr");
    }

    @Test
    void test_params_request_field_overrides_the_real_saved_value() throws Exception {
        UserAccount user = mockUser();
        when(experimentService.evaluateGate(any(), eq("chat.commands"))).thenReturn(true);

        UUID id = UUID.randomUUID();
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setId(id);
        def.setUser(user);
        def.setName("!lang");
        def.setTemplate("{ctx.params.language}");
        def.setAst(new NodeJsonCodec().toJson(new CommandDslParser().parse("{ctx.params.language}")));
        def.setPermission(ChatCommandEvent.SenderRole.EVERYONE);
        when(repository.findById(id)).thenReturn(Optional.of(def));
        ChatCommandParam savedParam = new ChatCommandParam();
        savedParam.setKey("language");
        savedParam.setValue("fr");
        when(paramRepository.findByUser(user)).thenReturn(java.util.List.of(savedParam));
        when(jsCompiler.compileWithTrace(any())).thenReturn("return ctx.params.language;");
        var trace = new fr.enimaloc.catapult.chat.command.trace.ExecutionTrace();
        trace.finish("en");
        when(sandboxExecutor.executeWithTrace(any(), any(), any(), any(), any(), any(), any())).thenReturn(trace);

        mvc.perform(withAdmin(post("/api/chat-commands/{id}/test", id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("overrides", Map.of(), "params", Map.of("language", "en")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output").value("en"));

        ArgumentCaptor<SandboxExecutor.ParamContext> paramCaptor =
                ArgumentCaptor.forClass(SandboxExecutor.ParamContext.class);
        verify(sandboxExecutor).executeWithTrace(any(), any(), any(), any(), any(), paramCaptor.capture(), any());
        assertThat(paramCaptor.getValue().resolve("language")).isEqualTo("en");
    }
}
