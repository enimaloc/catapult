package fr.enimaloc.catapult.web.template;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.security.CatapultOAuth2UserService;
import fr.enimaloc.catapult.service.*;
import fr.enimaloc.catapult.web.ChannelController;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChannelController.class)
@TestPropertySource(properties = {
    "spring.messages.basename=lang/messages",
    "spring.messages.use-code-as-default-message=false",
    "twitch.default-no-game.name=Just Chatting",
    "twitch.default-no-game.id=509658"
})
class AppTemplateTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean GameBindingRepository gameBindingRepository;
    @MockitoBean UserAccountRepository userAccountRepository;
    @MockitoBean UserSettingsRepository userSettingsRepository;
    @MockitoBean BindingService bindingService;
    @MockitoBean TwitchService twitchService;
    @MockitoBean CatapultOAuth2UserService oAuth2UserService;
    @MockitoBean GameStateService gameStateService;
    @MockitoBean ActivityLogService activityLogService;
    @MockitoBean AdminCclService adminCclService;
    @MockitoBean AccountService accountService;
    @MockitoBean StreamStateService streamStateService;
    @MockitoBean ConnectionEventService connectionEventService;
    @MockitoBean ExperimentService experimentService;
    @MockitoBean EventSubService twitchEventSubService;
    @MockitoBean ChannelAccessService channelAccessService;

    private UsernamePasswordAuthenticationToken auth;
    private UserAccount userAccount;

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }
    }

    @BeforeEach
    void setUp() {
        userAccount = new UserAccount();
        userAccount.setId(UUID.randomUUID());
        userAccount.setTwitchId("twitch-123");
        userAccount.setTwitchUsername("streamer");
        userAccount.setStatus(UserAccount.Status.ACTIVE);

        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttributes()).thenReturn(Map.of());
        CatapultOAuth2User principal = new CatapultOAuth2User(oAuth2User, userAccount, false);

        auth = new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(userAccount));
        when(channelAccessService.canAccess(any(), any())).thenReturn(true);
        when(gameBindingRepository.findByUser(any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(gameStateService.getLastKnownGame(any())).thenReturn(Optional.empty());
        when(adminCclService.getAllCcls()).thenReturn(List.of());
        when(experimentService.getActiveAssignments(any())).thenReturn(List.of());
        when(userSettingsRepository.findById(any())).thenReturn(Optional.of(new UserSettings()));
    }

    @Test
    void app_htmlHasLangAttribute() throws Exception {
        Document doc = renderPage();
        assertThat(doc.select("html[lang]")).isNotEmpty();
    }

    @Test
    void app_titleIsNotBlank() throws Exception {
        Document doc = renderPage();
        assertThat(doc.select("title").text()).isNotBlank();
    }

    @Test
    void app_deleteModalIsPresent() throws Exception {
        Document doc = renderPage();
        assertThat(doc.select("#deleteModal")).isNotEmpty();
    }

    @Test
    void app_confirmationInputHasAriaLabel() throws Exception {
        Document doc = renderPage();
        assertThat(doc.select("#deleteModal input[aria-label]")).isNotEmpty();
    }

    @Test
    void app_confirmationInputIsRequired() throws Exception {
        Document doc = renderPage();
        assertThat(doc.select("#deleteModal input[required]")).isNotEmpty();
    }

    @Test
    void app_noUnresolvedI18nKeys() throws Exception {
        Document doc = renderPage();
        assertThat(doc.body().text()).doesNotContain("??");
    }

    @Test
    void app_footerIsPresent() throws Exception {
        Document doc = renderPage();
        assertThat(doc.select("footer.footer")).isNotEmpty();
    }

    @Test
    void app_footerContainsPrivacyLink() throws Exception {
        Document doc = renderPage();
        assertThat(doc.select("footer a[href='/privacy']")).isNotEmpty();
    }

    @Test
    void app_footerContainsAppVersion() throws Exception {
        Document doc = renderPage();
        assertThat(doc.select("footer .footer-version")).isNotEmpty();
    }

    private Document renderPage() throws Exception {
        String html = mockMvc.perform(get("/channels/streamer").locale(Locale.FRENCH).with(authentication(auth)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return Jsoup.parse(html);
    }
}
