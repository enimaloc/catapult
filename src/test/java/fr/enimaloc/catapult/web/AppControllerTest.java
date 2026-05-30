package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.security.CatapultOAuth2UserService;
import fr.enimaloc.catapult.service.ExperimentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AppController.class)
class AppControllerTest {

    @TestConfiguration
    static class MessageSourceConfig {
        @Bean
        MessageSource messageSource() {
            var ms = new ReloadableResourceBundleMessageSource();
            ms.setBasename("classpath:lang/messages");
            ms.setDefaultEncoding("UTF-8");
            return ms;
        }
    }

    @Autowired MockMvc mockMvc;

    @MockitoBean CatapultOAuth2UserService oAuth2UserService;
    @MockitoBean ExperimentService experimentService;
    @MockitoSpyBean MessageSource messageSource;

    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setup() {
        UserAccount userAccount = new UserAccount();
        userAccount.setId(UUID.randomUUID());
        userAccount.setTwitchId("twitch-123");
        userAccount.setTwitchUsername("streamer");
        userAccount.setStatus(UserAccount.Status.ACTIVE);

        var oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttributes()).thenReturn(Map.of());
        var catapultUser = new CatapultOAuth2User(oAuth2User, userAccount, false);
        auth = new UsernamePasswordAuthenticationToken(
            catapultUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    void getApp_redirectsToChannels() throws Exception {
        mockMvc.perform(get("/app").with(authentication(auth)))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/channels"));
    }

    @Test
    void getDashboard_redirectsToChannels() throws Exception {
        mockMvc.perform(get("/dashboard").with(authentication(auth)))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/channels"));
    }

    @Test
    void getSettings_redirectsToChannels() throws Exception {
        mockMvc.perform(get("/settings").with(authentication(auth)))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/channels"));
    }

    @Test
    void helpPanel_noGame_passesUnifiedArgs() throws Exception {
        mockMvc.perform(get("/help/no-game").with(authentication(auth)))
            .andExpect(status().isOk());

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(messageSource).getMessage(eq("help.no-game.body"), argsCaptor.capture(), any(), any());
        Object[] args = argsCaptor.getValue();
        assertThat(args).hasSize(3);
        assertThat(args[0]).isEqualTo(7);           // deletionDelayDays from application.properties
        assertThat(args[1]).isEqualTo("Just Chatting"); // twitch.default-no-game.name
        assertThat(args[2]).isEqualTo("Catapult");  // spring.application.name
    }

    @Test
    void helpPanel_dangerZone_passesUnifiedArgs() throws Exception {
        mockMvc.perform(get("/help/danger-zone").with(authentication(auth)))
            .andExpect(status().isOk());

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(messageSource).getMessage(eq("help.danger-zone.body"), argsCaptor.capture(), any(), any());
        Object[] args = argsCaptor.getValue();
        assertThat(args).hasSize(3);
        assertThat(args[0]).isEqualTo(7);
    }
}
