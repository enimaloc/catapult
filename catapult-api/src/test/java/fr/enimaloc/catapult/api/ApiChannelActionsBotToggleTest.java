package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.SteamApiKeyRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.AccountService;
import fr.enimaloc.catapult.service.BindingService;
import fr.enimaloc.catapult.service.BotToggleService;
import fr.enimaloc.catapult.service.ChannelAccessService;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.SchedulerService;
import fr.enimaloc.catapult.service.TwitchService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.notification.TwitchatNotifier;
import fr.enimaloc.catapult.service.notification.TwitchatPayloadPresetService;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiChannelActionsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
class ApiChannelActionsBotToggleTest {

    @Autowired MockMvc mvc;

    @MockitoBean UserAccountRepository userAccountRepository;
    @MockitoBean UserSettingsRepository userSettingsRepository;
    @MockitoBean ChannelAccessService channelAccessService;
    @MockitoBean BindingService bindingService;
    @MockitoBean BotToggleService botToggleService;
    @MockitoBean TwitchService twitchService;
    @MockitoBean GameStateService gameStateService;
    @MockitoBean AccountService accountService;
    @MockitoBean TokenEncryptionService tokenEncryptionService;
    @MockitoBean SteamApiKeyRepository steamApiKeyRepository;
    @MockitoBean ChannelEventPublisher channelEventPublisher;
    @MockitoBean TwitchatWidgetSettingsService twitchatWidgetSettingsService;
    @MockitoBean TwitchatPayloadPresetService twitchatPayloadPresetService;
    @MockitoBean TwitchatNotifier twitchatNotifier;
    @MockitoBean SchedulerService schedulerService;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor userJwt(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()).claim("twitchId", "123"))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    void toggleBot_ownerTogglesOwnBot_delegatesToBotToggleService() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);
        user.setTwitchUsername("streamer");
        user.setBotEnabled(true);

        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(user));
        when(channelAccessService.canAccess(user, user)).thenReturn(true);

        mvc.perform(post("/api/channels/streamer/settings/bot")
                        .with(userJwt(userId))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(botToggleService).setBotEnabled(user, false);
    }

    @Test
    void toggleBot_nonOwner_forbidden() throws Exception {
        UUID viewerId = UUID.randomUUID();
        UserAccount viewer = new UserAccount();
        viewer.setId(viewerId);

        UUID channelId = UUID.randomUUID();
        UserAccount channelUser = new UserAccount();
        channelUser.setId(channelId);
        channelUser.setTwitchUsername("streamer");
        channelUser.setBotEnabled(true);

        when(userAccountRepository.findById(viewerId)).thenReturn(Optional.of(viewer));
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(channelUser));
        when(channelAccessService.canAccess(viewer, channelUser)).thenReturn(true);

        mvc.perform(post("/api/channels/streamer/settings/bot")
                        .with(userJwt(viewerId))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
