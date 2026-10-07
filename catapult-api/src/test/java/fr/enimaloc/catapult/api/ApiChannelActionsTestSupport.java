package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.steam.SteamApiKeyRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.account.AccountService;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import fr.enimaloc.catapult.service.binding.BindingService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.binding.SchedulerService;
import fr.enimaloc.catapult.service.connections.SteamProfileDiagnostics;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.notification.TwitchatNotifier;
import fr.enimaloc.catapult.service.notification.TwitchatPayloadPresetService;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetSettingsService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/** Shared slice for the ApiChannelActionsController tests: every collaborator mocked. */
@WebMvcTest(
        controllers = ApiChannelActionsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import(ApiUserResolver.class)
abstract class ApiChannelActionsTestSupport {

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
    @MockitoBean SchedulerService schedulerService;
    @MockitoBean TwitchatWidgetSettingsService twitchatWidgetSettingsService;
    @MockitoBean TwitchatPayloadPresetService twitchatPayloadPresetService;
    @MockitoBean TwitchatNotifier twitchatNotifier;
    @MockitoBean SteamProfileDiagnostics steamProfileDiagnostics;

    static RequestPostProcessor userJwt(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()).claim("twitchId", "123"))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    /** A known account, found by its id and its username. */
    UserAccount registered(String username) {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchUsername(username);
        when(userAccountRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userAccountRepository.findByTwitchUsername(username)).thenReturn(Optional.of(user));
        return user;
    }
}
