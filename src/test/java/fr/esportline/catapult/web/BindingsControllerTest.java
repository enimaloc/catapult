package fr.esportline.catapult.web;

import fr.esportline.catapult.domain.GameBinding;
import fr.esportline.catapult.domain.TwitchCcl;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.repository.GameBindingRepository;
import fr.esportline.catapult.security.CatapultOAuth2User;
import fr.esportline.catapult.security.CatapultOAuth2UserService;
import fr.esportline.catapult.service.BindingService;
import fr.esportline.catapult.service.TwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BindingsController.class)
class BindingsControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean GameBindingRepository gameBindingRepository;
    @MockitoBean BindingService bindingService;
    @MockitoBean TwitchService twitchService;
    @MockitoBean CatapultOAuth2UserService oAuth2UserService;  // needed by SecurityConfig

    private UserAccount userAccount;
    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setup() {
        userAccount = new UserAccount();
        userAccount.setTwitchId("twitch-123");
        userAccount.setStatus(UserAccount.Status.ACTIVE);

        var oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttributes()).thenReturn(Map.of());
        var catapultUser = new CatapultOAuth2User(oAuth2User, userAccount);

        auth = new UsernamePasswordAuthenticationToken(
            catapultUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        when(gameBindingRepository.findByUser(any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
    }

    @Test
    void getBindings_rendersPage() throws Exception {
        mockMvc.perform(get("/bindings").with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(view().name("bindings"))
            .andExpect(model().attributeExists("bindings", "availableCcls"));
    }

    @Test
    void postUpdateBinding_redirectsToBindings() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/bindings/{id}", id)
                .with(csrf())
                .with(authentication(auth))
                .param("twitchGameId", "game-123")
                .param("twitchGameName", "My Game")
                .param("ccls", TwitchCcl.ViolentGraphic.name()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/bindings"));

        verify(bindingService).updateBinding(eq(userAccount), eq(id),
            eq("game-123"), eq("My Game"), eq(Set.of(TwitchCcl.ViolentGraphic)), eq(false));
    }

    @Test
    void postUpdateBinding_withNoCcls_passesEmptySet() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/bindings/{id}", id)
                .with(csrf())
                .with(authentication(auth))
                .param("twitchGameId", "game-123")
                .param("twitchGameName", "My Game"))
            .andExpect(status().is3xxRedirection());

        verify(bindingService).updateBinding(eq(userAccount), eq(id),
            eq("game-123"), eq("My Game"), eq(Set.of()), eq(false));
    }

    @Test
    void postCclToggle_redirectsToBindings() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/bindings/{id}/ccl-toggle", id)
                .with(csrf())
                .with(authentication(auth))
                .param("enabled", "true"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/bindings"));

        verify(bindingService).toggleCclEnabled(userAccount, id, true);
    }

    @Test
    void postIgnoredToggle_redirectsToBindings() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/bindings/{id}/ignored-toggle", id)
                .with(csrf())
                .with(authentication(auth))
                .param("ignored", "true"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/bindings"));

        verify(bindingService).toggleIgnored(userAccount, id, true);
    }

    @Test
    void postDelete_redirectsToBindings() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/bindings/{id}/delete", id)
                .with(csrf())
                .with(authentication(auth)))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/bindings"));

        verify(bindingService).deleteBinding(id);
    }

    @Test
    void getGameSearch_returnsTwitchCategories() throws Exception {
        when(twitchService.searchCategories(eq(userAccount), eq("fortnite")))
            .thenReturn(List.of(new TwitchService.TwitchCategory("1234", "Fortnite")));

        mockMvc.perform(get("/api/games/search")
                .with(authentication(auth))
                .param("q", "fortnite"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$[0].id").value("1234"))
            .andExpect(jsonPath("$[0].name").value("Fortnite"));
    }
}
