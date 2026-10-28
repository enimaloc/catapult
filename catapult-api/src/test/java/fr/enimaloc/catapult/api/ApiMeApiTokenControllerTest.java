package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.account.ApiTokenScope;
import fr.enimaloc.catapult.common.dto.account.ApiTokenCreatedDto;
import fr.enimaloc.catapult.common.dto.account.ApiTokenStatusDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.Optional;
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
        controllers = ApiMeApiTokenController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import(ApiUserResolver.class)
class ApiMeApiTokenControllerTest {

    private static final UUID SELF = UUID.randomUUID();
    private static final String OWN = "/api/internal/users/" + SELF;

    @Autowired MockMvc mvc;
    @MockitoBean UserApiTokenService tokens;
    @MockitoBean CustomBindingService customBindings;
    @MockitoBean UserAccountRepository accounts;
    @MockitoBean ChannelAccessService channelAccess;

    private UserAccount user;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(accounts.findById(user.getId())).thenReturn(Optional.of(user));
        when(customBindings.enabled(user)).thenReturn(true);
    }

    private RequestPostProcessor me() {
        return jwt().jwt(j -> j.subject(user.getId().toString()));
    }

    @Test
    void status_neverShowsTheToken() throws Exception {
        when(tokens.status(user, ApiTokenScope.FULL)).thenReturn(new ApiTokenStatusDto(true, Instant.parse("2026-10-24T10:00:00Z")));

        mvc.perform(get(OWN + "/api-token").with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.createdAt").value("2026-10-24T10:00:00Z"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void regenerate_showsTheNewTokenOnce() throws Exception {
        when(tokens.regenerate(user, ApiTokenScope.FULL)).thenReturn(new ApiTokenCreatedDto("cat_abc", Instant.EPOCH));

        mvc.perform(post(OWN + "/api-token").with(me()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("cat_abc"));
    }

    @Test
    void delete_revokesIt() throws Exception {
        mvc.perform(delete(OWN + "/api-token").with(me()).with(csrf()))
                .andExpect(status().isNoContent());
        verify(tokens).revoke(user, ApiTokenScope.FULL);
    }

    @Test
    void gateClosed_noTokenToGetOrMake_butOneCanStillBeRevoked() throws Exception {
        when(customBindings.enabled(user)).thenReturn(false);

        mvc.perform(get(OWN + "/api-token").with(me()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("feature_disabled"));
        mvc.perform(post(OWN + "/api-token").with(me()).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("feature_disabled"));
        mvc.perform(delete(OWN + "/api-token").with(me()).with(csrf())).andExpect(status().isNoContent());
        verify(tokens, never()).regenerate(any(), any());
        verify(tokens).revoke(user, ApiTokenScope.FULL);
    }

    @Test
    void readToken_isOfferedOutsideTheCustomBindingsExperiment() throws Exception {
        when(customBindings.enabled(user)).thenReturn(false);
        when(tokens.status(user, ApiTokenScope.READ)).thenReturn(new ApiTokenStatusDto(true, Instant.EPOCH));
        when(tokens.regenerate(user, ApiTokenScope.READ)).thenReturn(new ApiTokenCreatedDto("catr_abc", Instant.EPOCH));

        mvc.perform(get(OWN + "/api-token/read").with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.token").doesNotExist());
        mvc.perform(post(OWN + "/api-token/read").with(me()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("catr_abc"));
        mvc.perform(post(OWN + "/api-token/read/delete").with(me()).with(csrf()))
                .andExpect(status().isNoContent());
        verify(tokens).revoke(user, ApiTokenScope.READ);
    }

    @Test
    void fullToken_stillRequiresTheExperiment() throws Exception {
        when(customBindings.enabled(user)).thenReturn(false);

        mvc.perform(post(OWN + "/api-token").with(me()).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("feature_disabled"));
        verify(tokens, never()).regenerate(any(), eq(ApiTokenScope.FULL));
    }
}
