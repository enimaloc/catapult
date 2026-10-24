package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.api.CustomBindingViews;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.ApiSecurityConfig;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import fr.enimaloc.catapult.service.binding.CustomBindingService.ActivationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = CustomBindingsApiV2Controller.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({ApiSecurityConfig.class, CustomBindingsApiV2ControllerTest.Keys.class, CustomBindingViews.class})
@ActiveProfiles("test")
class CustomBindingsApiV2ControllerTest {

    private static final String TOKEN = "cat_" + "t".repeat(43);

    @Autowired MockMvc mvc;
    @MockitoBean UserApiTokenService tokens;
    @MockitoBean CustomBindingService service;
    @MockitoBean UserAccountRepository accounts;

    private UserAccount user;
    private CustomBinding art;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchUsername("enimaloc");
        when(accounts.findById(user.getId())).thenReturn(Optional.of(user));
        when(tokens.authenticate(TOKEN)).thenReturn(Optional.of(user));
        when(service.enabled(any())).thenReturn(true);
        art = new CustomBinding();
        art.setId(UUID.randomUUID());
        art.setUser(user);
        art.setName("Art");
        art.setTwitchGameId("509660");
        art.setTwitchGameName("Art");
        art.getCcls().add("ProfanityVulgarity");
        art.getTws().add("chill");
        art.getTws().add("art");
        art.setUninterruptible(true);
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TOKEN);
    }

    @Test
    void list_givesTheBindings_andTheActiveOne() throws Exception {
        Instant at = Instant.parse("2026-10-24T10:00:00Z");
        when(service.list(user)).thenReturn(List.of(art));
        when(service.active(user)).thenReturn(Optional.of(new CustomBindingActivation(user.getId(), art, at)));

        mvc.perform(withToken(get("/api/v2/custom-bindings")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.activeId").value(art.getId().toString()))
                .andExpect(jsonPath("$.activatedAt").value("2026-10-24T10:00:00Z"))
                .andExpect(jsonPath("$.bindings[0].id").value(art.getId().toString()))
                .andExpect(jsonPath("$.bindings[0].name").value("Art"))
                .andExpect(jsonPath("$.bindings[0].twitchGameId").value("509660"))
                .andExpect(jsonPath("$.bindings[0].twitchGameName").value("Art"))
                .andExpect(jsonPath("$.bindings[0].ccls[0]").value("ProfanityVulgarity"))
                .andExpect(jsonPath("$.bindings[0].tws[0]").value("art"))
                .andExpect(jsonPath("$.bindings[0].tws[1]").value("chill"))
                .andExpect(jsonPath("$.bindings[0].uninterruptible").value(true));
    }

    @Test
    void list_withNoneActive() throws Exception {
        when(service.list(user)).thenReturn(List.of());
        when(service.active(user)).thenReturn(Optional.empty());

        mvc.perform(withToken(get("/api/v2/custom-bindings")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindings").isEmpty())
                .andExpect(jsonPath("$.activeId").doesNotExist());
    }

    @Test
    void personalToken_activatesByNameIgnoringCase() throws Exception {
        when(service.resolve(user, "art")).thenReturn(Optional.of(art));
        when(service.activate(user, art)).thenReturn(ActivationResult.APPLIED);

        mvc.perform(withToken(post("/api/v2/custom-bindings/art/activate")))
                .andExpect(status().isNoContent());

        verify(service).activate(user, art);
    }

    @Test
    void activatesById() throws Exception {
        when(service.resolve(user, art.getId().toString())).thenReturn(Optional.of(art));
        when(service.activate(user, art)).thenReturn(ActivationResult.APPLIED);

        mvc.perform(withToken(post("/api/v2/custom-bindings/" + art.getId() + "/activate")))
                .andExpect(status().isNoContent());
    }

    @Test
    void unknownBinding_is404() throws Exception {
        when(service.resolve(any(), anyString())).thenReturn(Optional.empty());

        mvc.perform(withToken(post("/api/v2/custom-bindings/nope/activate")))
                .andExpect(status().isNotFound());

        verify(service, never()).activate(any(), any());
    }

    @Test
    void twitchUnavailable_is409_askingToReconnect() throws Exception {
        when(service.resolve(user, "art")).thenReturn(Optional.of(art));
        when(service.activate(user, art)).thenReturn(ActivationResult.TWITCH_UNAVAILABLE);

        mvc.perform(withToken(post("/api/v2/custom-bindings/art/activate")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("twitch_unavailable"))
                .andExpect(jsonPath("$.message").value("Reconnecte ton compte Twitch"));
    }

    @Test
    void botDisabled_is409_withItsOwnCode() throws Exception {
        when(service.resolve(user, "art")).thenReturn(Optional.of(art));
        when(service.activate(user, art)).thenReturn(ActivationResult.BOT_DISABLED);

        mvc.perform(withToken(post("/api/v2/custom-bindings/art/activate")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("bot_disabled"))
                .andExpect(jsonPath("$.message").value("Catapult est en pause sur cette chaîne"));
    }

    @Test
    void twitchFailure_is502() throws Exception {
        when(service.resolve(user, "art")).thenReturn(Optional.of(art));
        when(service.activate(user, art)).thenReturn(ActivationResult.FAILED);

        mvc.perform(withToken(post("/api/v2/custom-bindings/art/activate")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("twitch_failed"));
    }

    @Test
    void deactivate_is204() throws Exception {
        mvc.perform(withToken(post("/api/v2/custom-bindings/deactivate")))
                .andExpect(status().isNoContent());

        verify(service).deactivate(user);
    }

    @Test
    void gateClosed_is403() throws Exception {
        ResponseStatusException closed = new ResponseStatusException(HttpStatus.FORBIDDEN, "Feature not enabled");
        when(service.list(any())).thenThrow(closed);
        when(service.resolve(any(), anyString())).thenThrow(closed);
        doThrow(closed).when(service).deactivate(any());

        mvc.perform(withToken(get("/api/v2/custom-bindings"))).andExpect(status().isForbidden());
        mvc.perform(withToken(post("/api/v2/custom-bindings/art/activate"))).andExpect(status().isForbidden());
        mvc.perform(withToken(post("/api/v2/custom-bindings/deactivate"))).andExpect(status().isForbidden());
        verify(service, never()).activate(any(), any());
    }

    @Test
    void aTokenWhoseAccountIsGone_is401() throws Exception {
        when(accounts.findById(user.getId())).thenReturn(Optional.empty());

        mvc.perform(withToken(post("/api/v2/custom-bindings/deactivate"))).andExpect(status().isUnauthorized());
        verify(service, never()).deactivate(any());
    }

    @TestConfiguration
    static class Keys {
        @Bean
        SecretKey jwtSecretKey() {
            return new SecretKeySpec(new byte[32], "HmacSHA256");
        }
    }
}
