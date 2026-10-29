package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.channel.ApiChannelActionsController;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.steam.SteamApiKeyRepository;
import fr.enimaloc.catapult.security.Authz;
import fr.enimaloc.catapult.security.MethodSecurityConfig;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.account.AccountService;
import fr.enimaloc.catapult.service.account.ChannelAccountActions;
import fr.enimaloc.catapult.service.account.ChannelSettingsService;
import fr.enimaloc.catapult.service.binding.ChannelDashboardActions;
import fr.enimaloc.catapult.service.steam.SteamPersonalTokenService;
import fr.enimaloc.catapult.service.steam.SteamPersonalTokenStore;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import fr.enimaloc.catapult.service.binding.BindingService;
import fr.enimaloc.catapult.service.binding.CustomBindingActivations;
import fr.enimaloc.catapult.service.binding.GameBindingLookup;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.binding.RaidRestoreGateService;
import fr.enimaloc.catapult.service.binding.RaidRestoreHold;
import fr.enimaloc.catapult.service.binding.SchedulerService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.category.CategoryApplier;
import fr.enimaloc.catapult.service.category.CategoryChain;
import fr.enimaloc.catapult.service.category.ExtensionLayoutApplier;
import fr.enimaloc.catapult.service.connections.SteamProfileDiagnostics;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.notification.NotificationService;
import fr.enimaloc.catapult.service.notification.TwitchatEventActionService;
import fr.enimaloc.catapult.service.notification.TwitchatEventDispatcher;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetSettingsService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Each account a request touches is read once, however many {@code @authz} checks and handler lookups need it, and
 * the 401 (caller), 404 (target), 403 order stays as it was.
 */
@WebMvcTest(
        controllers = {ApiChannelActionsController.class, ApiNotificationsController.class},
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({ApiUserResolver.class, MethodSecurityConfig.class, Authz.class, CategoryChain.class, CategoryApplier.class,
        ChannelSettingsService.class, ChannelDashboardActions.class, SteamPersonalTokenService.class,
        SteamPersonalTokenStore.class, ChannelAccountActions.class,
        RaidRestoreHold.class})
class AccountResolutionPerRequestTest {

    @Autowired MockMvc mvc;

    @MockitoBean UserAccountRepository userAccountRepository;
    @MockitoBean UserSettingsRepository userSettingsRepository;
    @MockitoBean ChannelAccessService channelAccessService;
    @MockitoBean BindingService bindingService;
    @MockitoBean BotToggleService botToggleService;
    @MockitoBean TwitchService twitchService;
    @MockitoBean GameStateService gameStateService;
    @MockitoBean StreamStateService streamStateService;
    @MockitoBean AccountService accountService;
    @MockitoBean TokenEncryptionService tokenEncryptionService;
    @MockitoBean SteamApiKeyRepository steamApiKeyRepository;
    @MockitoBean ChannelEventPublisher channelEventPublisher;
    @MockitoBean SchedulerService schedulerService;
    @MockitoBean TwitchatWidgetSettingsService twitchatWidgetSettingsService;
    @MockitoBean TwitchatEventActionService twitchatEventActionService;
    @MockitoBean TwitchatEventDispatcher twitchatEventDispatcher;
    @MockitoBean SteamProfileDiagnostics steamProfileDiagnostics;
    @MockitoBean RaidRestoreGateService raidRestoreGate;
    @MockitoBean ExtensionLayoutApplier extensionLayoutApplier;
    @MockitoBean GameBindingLookup gameBindingLookup;
    @MockitoBean CustomBindingActivations customBindingActivations;
    @MockitoBean NotificationService notificationService;

    private static RequestPostProcessor as(UserAccount account) {
        return jwt().jwt(j -> j.subject(account.getId().toString()).claim("twitchId", account.getTwitchId()))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private UserAccount registered(String username) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchUsername(username);
        account.setTwitchId(username + "-id");
        when(userAccountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(userAccountRepository.findByTwitchId(account.getTwitchId())).thenReturn(Optional.of(account));
        return account;
    }

    @Test
    void ownsChannel_route_readsTheOwnerOnce() throws Exception {
        UserAccount owner = registered("streamer");
        owner.setWidgetToken(UUID.randomUUID());
        TwitchatWidgetSettings settings = new TwitchatWidgetSettings();
        settings.setUser(owner);
        when(twitchatWidgetSettingsService.getOrCreate(owner)).thenReturn(settings);

        mvc.perform(get("/api/internal/users/{userId}/settings/twitchat", owner.getId()).with(as(owner)))
                .andExpect(status().isOk());

        verify(userAccountRepository, times(1)).findById(owner.getId());
    }

    @Test
    void canAccessChannel_route_readsViewerAndChannelOnceEach() throws Exception {
        UserAccount owner = registered("streamer");
        UserAccount moderator = registered("mod");
        when(channelAccessService.canAccess(moderator, owner)).thenReturn(true);

        mvc.perform(put("/api/internal/users/{userId}/bindings/{bindingId}/ignored", owner.getId(), UUID.randomUUID())
                        .with(as(moderator)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ignored\":true}"))
                .andExpect(status().isNoContent());

        verify(userAccountRepository, times(1)).findById(moderator.getId());
        verify(userAccountRepository, times(1)).findById(owner.getId());
    }

    @Test
    void isSelf_routeResolvingByTwitchId_readsOnce() throws Exception {
        UserAccount me = registered("me");

        mvc.perform(get("/api/internal/users/{userId}/notifications/unread-count", me.getId()).with(as(me)))
                .andExpect(status().isOk());

        verify(userAccountRepository, times(1)).findById(me.getId());
        verify(userAccountRepository, never()).findByTwitchId(any());
    }

    @Test
    void unknownCaller_is401_unknownTarget_is404() throws Exception {
        UserAccount owner = registered("streamer");
        UserAccount ghost = new UserAccount();
        ghost.setId(UUID.randomUUID());
        ghost.setTwitchId("ghost-id");
        UUID unknownChannel = UUID.randomUUID();

        mvc.perform(get("/api/internal/users/{userId}/settings/twitchat", unknownChannel).with(as(ghost)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/users/{userId}/settings/twitchat", unknownChannel).with(as(owner)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/internal/users/{userId}/notifications/unread-count", unknownChannel).with(as(owner)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/internal/users/{userId}/notifications/unread-count", owner.getId()).with(as(ghost)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/users/{userId}/settings/twitchat", owner.getId()).with(as(registered("other"))))
                .andExpect(status().isForbidden());

        verify(userAccountRepository, times(2)).findById(unknownChannel);
    }
}
