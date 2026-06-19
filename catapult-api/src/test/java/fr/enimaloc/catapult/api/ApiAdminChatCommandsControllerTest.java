package fr.enimaloc.catapult.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.chat.ChatCommandPresetCatalog;
import fr.enimaloc.catapult.chat.PlaceholderResolver;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.service.SystemTwitchAccountService;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiAdminChatCommandsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
class ApiAdminChatCommandsControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();
    @MockitoBean ChatCommandDefinitionRepository repository;
    @MockitoBean ChatCommandPresetCatalog catalog;
    @MockitoBean PlaceholderResolver placeholderResolver;
    @MockitoBean ExperimentService experimentService;
    @MockitoBean SystemTwitchAccountService systemAccount;
    @MockitoBean UserAccountRepository userRepo;

    private UserAccount mockUser() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("99");
        user.setTwitchUsername("streamer");
        when(userRepo.findByTwitchId(any())).thenReturn(Optional.of(user));
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
    void get_returns_403_when_not_rolled_out() throws Exception {
        mockUser();
        when(experimentService.evaluateGate(any(), eq("chat.commands"))).thenReturn(false);

        mvc.perform(withAdmin(get("/api/admin/chat-commands")))
                .andExpect(status().isForbidden());
    }

    @Test
    void get_returns_list_when_rolled_out() throws Exception {
        mockUser();
        when(experimentService.evaluateGate(any(), eq("chat.commands"))).thenReturn(true);
        when(repository.findByUser(any())).thenReturn(List.of());
        when(catalog.allKeys()).thenReturn(Set.of("game", "store"));
        when(systemAccount.check(any(), any()))
                .thenReturn(new SystemTwitchAccountService.BotModStatus(false, Instant.now()));

        mvc.perform(withAdmin(get("/api/admin/chat-commands")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.presets").isArray())
                .andExpect(jsonPath("$.commands").isArray())
                .andExpect(jsonPath("$.botModStatus").exists())
                .andExpect(jsonPath("$.botModStatus.modded").value(false));
    }

    @Test
    void post_invalid_name_returns_400() throws Exception {
        mockUser();
        when(experimentService.evaluateGate(any(), eq("chat.commands"))).thenReturn(true);

        String body = om.writeValueAsString(Map.of(
                "name", "noBang",
                "template", "Hi",
                "permission", "EVERYONE",
                "enabled", true));

        mvc.perform(withAdmin(post("/api/admin/chat-commands"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(repository, never()).save(any());
    }

    @Test
    void post_with_unknown_placeholder_returns_400() throws Exception {
        mockUser();
        when(experimentService.evaluateGate(any(), eq("chat.commands"))).thenReturn(true);
        when(placeholderResolver.findUnknownPaths("Hi {game.unknown}"))
                .thenReturn(Set.of("game.unknown"));

        String body = om.writeValueAsString(Map.of(
                "name", "!foo",
                "template", "Hi {game.unknown}",
                "permission", "EVERYONE",
                "enabled", true));

        mvc.perform(withAdmin(post("/api/admin/chat-commands"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(repository, never()).save(any());
    }

    @Test
    void post_creates_successfully() throws Exception {
        UserAccount user = mockUser();
        when(experimentService.evaluateGate(any(), eq("chat.commands"))).thenReturn(true);
        when(placeholderResolver.findUnknownPaths(any())).thenReturn(Set.of());
        when(repository.existsByUserAndName(any(), eq("!foo"))).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> {
            ChatCommandDefinition d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });

        String body = om.writeValueAsString(Map.of(
                "name", "!foo",
                "template", "Hi",
                "permission", "EVERYONE",
                "enabled", true));

        mvc.perform(withAdmin(post("/api/admin/chat-commands"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("!foo"))
                .andExpect(jsonPath("$.template").value("Hi"))
                .andExpect(jsonPath("$.permission").value("EVERYONE"));

        verify(repository).save(any(ChatCommandDefinition.class));
    }

    @Test
    void delete_returns_404_if_user_does_not_own_command() throws Exception {
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

        mvc.perform(withAdmin(delete("/api/admin/chat-commands/{id}", otherId)))
                .andExpect(status().isNotFound());

        verify(repository, never()).delete(any(ChatCommandDefinition.class));
    }
}
