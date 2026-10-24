package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.CustomBindingViews;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingRequest;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import fr.enimaloc.catapult.service.binding.CustomBindingService.ActivationResult;
import fr.enimaloc.catapult.service.binding.CustomBindingService.UpdateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiChannelCustomBindingsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({ApiUserResolver.class, CustomBindingViews.class})
class ApiChannelCustomBindingsControllerTest {

    private static final String BODY = """
            {"name":"Art","twitchGameId":"509660","twitchGameName":"Art","ccls":["ProfanityVulgarity"],
             "tws":["chill"],"uninterruptible":true}""";
    private static final CustomBindingRequest REQUEST = new CustomBindingRequest("Art", "509660", "Art",
            Set.of("ProfanityVulgarity"), Set.of("chill"), true);

    @Autowired MockMvc mvc;
    @MockitoBean UserAccountRepository accounts;
    @MockitoBean ChannelAccessService channelAccess;
    @MockitoBean CustomBindingService service;

    private UserAccount owner;
    private UserAccount other;
    private CustomBinding art;

    @BeforeEach
    void setUp() {
        owner = registered("enimaloc");
        other = registered("someone");
        // A moderator of the channel, even: still not its owner
        when(channelAccess.canAccess(any(), any())).thenReturn(true);
        art = new CustomBinding();
        art.setId(UUID.randomUUID());
        art.setUser(owner);
        art.setName("Art");
        art.setTwitchGameId("509660");
        art.setTwitchGameName("Art");
    }

    private UserAccount registered(String username) {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchUsername(username);
        when(accounts.findById(user.getId())).thenReturn(Optional.of(user));
        when(accounts.findByTwitchUsername(username)).thenReturn(Optional.of(user));
        return user;
    }

    private static RequestPostProcessor as(UserAccount user) {
        return jwt().jwt(j -> j.subject(user.getId().toString()).claim("twitchId", "123"))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static final String BASE = "/api/channels/enimaloc/custom-bindings";

    private MockHttpServletRequestBuilder postJson(String path, UserAccount as) {
        return post(BASE + path).contentType(MediaType.APPLICATION_JSON).content(BODY).with(as(as)).with(csrf());
    }

    @Test
    void ownerEndpoints_rejectAnotherMember() throws Exception {
        mvc.perform(get(BASE).with(as(other))).andExpect(status().isForbidden());
        mvc.perform(postJson("", other)).andExpect(status().isForbidden());
        mvc.perform(postJson("/" + art.getId(), other)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/" + art.getId() + "/delete").with(as(other)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/" + art.getId() + "/activate").with(as(other)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/deactivate").with(as(other)).with(csrf())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void anonymous_is401() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void state_listsTheOwnersBindings() throws Exception {
        when(service.list(owner)).thenReturn(List.of(art));

        mvc.perform(get(BASE).with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.bindings[0].name").value("Art"));
    }

    @Test
    void create_answersTheSavedBinding() throws Exception {
        when(service.create(owner, REQUEST)).thenReturn(art);

        mvc.perform(postJson("", owner))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(art.getId().toString()))
                .andExpect(jsonPath("$.name").value("Art"));
    }

    @Test
    void update_ofAnInactiveBinding_hasNoReapplyOutcome() throws Exception {
        when(service.update(owner, art.getId(), REQUEST)).thenReturn(new UpdateResult(art, null));

        mvc.perform(postJson("/" + art.getId(), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.binding.id").value(art.getId().toString()))
                .andExpect(jsonPath("$.reapplyOutcome").doesNotExist());
    }

    @Test
    void update_ofTheActiveBinding_tellsHowReapplyingWent_evenWhenTwitchRefused() throws Exception {
        when(service.update(owner, art.getId(), REQUEST))
                .thenReturn(new UpdateResult(art, ActivationResult.TWITCH_UNAVAILABLE));

        mvc.perform(postJson("/" + art.getId(), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.binding.name").value("Art"))
                .andExpect(jsonPath("$.reapplyOutcome").value("twitch_unavailable"));

        when(service.update(owner, art.getId(), REQUEST)).thenReturn(new UpdateResult(art, ActivationResult.APPLIED));
        mvc.perform(postJson("/" + art.getId(), owner))
                .andExpect(jsonPath("$.reapplyOutcome").value("applied"));
    }

    @Test
    void delete_is204() throws Exception {
        mvc.perform(post(BASE + "/" + art.getId() + "/delete").with(as(owner)).with(csrf()))
                .andExpect(status().isNoContent());
        verify(service).delete(owner, art.getId());
    }

    @Test
    void activate_appliesTheOwnersBinding() throws Exception {
        when(service.resolve(owner, art.getId().toString())).thenReturn(Optional.of(art));
        when(service.activate(owner, art)).thenReturn(ActivationResult.APPLIED);

        mvc.perform(post(BASE + "/" + art.getId() + "/activate").with(as(owner)).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void activate_mapsTwitchOutcomes() throws Exception {
        when(service.resolve(owner, art.getId().toString())).thenReturn(Optional.of(art));
        when(service.activate(owner, art)).thenReturn(ActivationResult.BOT_DISABLED);
        mvc.perform(post(BASE + "/" + art.getId() + "/activate").with(as(owner)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("bot_disabled"));

        when(service.activate(owner, art)).thenReturn(ActivationResult.FAILED);
        mvc.perform(post(BASE + "/" + art.getId() + "/activate").with(as(owner)).with(csrf()))
                .andExpect(status().isBadGateway());
    }

    @Test
    void activate_unknown_is404() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(service.resolve(eq(owner), any())).thenReturn(Optional.empty());

        mvc.perform(post(BASE + "/" + unknown + "/activate").with(as(owner)).with(csrf()))
                .andExpect(status().isNotFound());
        verify(service, never()).activate(any(), any());
    }

    @Test
    void deactivate_is204() throws Exception {
        mvc.perform(post(BASE + "/deactivate").with(as(owner)).with(csrf())).andExpect(status().isNoContent());
        verify(service).deactivate(owner);
    }

    @Test
    void gateClosed_is403() throws Exception {
        when(service.list(owner)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Feature not enabled"));

        mvc.perform(get(BASE).with(as(owner))).andExpect(status().isForbidden());
    }
}
