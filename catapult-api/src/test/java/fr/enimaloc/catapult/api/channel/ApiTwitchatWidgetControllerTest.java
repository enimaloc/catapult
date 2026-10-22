package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.config.I18nConfig;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import fr.enimaloc.catapult.repository.twitchat.TwitchatWidgetSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.notification.NotificationDeliveryDispatcher;
import fr.enimaloc.catapult.service.notification.TwitchatActionExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiTwitchatWidgetController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
// Filters are disabled class-wide because /api/twitchat/widget/** and /api/twitchat/actions/**
// are exercised with plain tokens (no auth header) below; those paths are permitAll in
// ApiSecurityConfig, so this does not hide an auth regression on them. If a future test in this
// class needs to assert permitAll/auth behavior itself, scope addFilters=false to a narrower
// @Nested class instead of widening it here.
@AutoConfigureMockMvc(addFilters = false)
@Import(I18nConfig.class)
class ApiTwitchatWidgetControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean TwitchatWidgetSettingsRepository widgetSettingsRepository;
    @MockitoBean WidgetTokenService widgetTokenService;
    @MockitoBean TokenEncryptionService tokenEncryptionService;
    @MockitoBean TwitchatActionExecutor actionExecutor;
    @MockitoBean NotificationDeliveryDispatcher deliveryDispatcher;

    @Test
    void flushNotifications_byTheWidgetsToken_sendsItsOwnersPendingOnes() throws Exception {
        UUID token = UUID.randomUUID();
        UserAccount owner = new UserAccount();
        owner.setId(UUID.randomUUID());
        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(owner));
        TwitchatWidgetSettings settings = new TwitchatWidgetSettings();
        settings.setEnabled(true);
        when(widgetSettingsRepository.findById(owner.getId())).thenReturn(Optional.of(settings));
        when(deliveryDispatcher.flushTwitchat(owner)).thenReturn(3);

        mvc.perform(post("/api/twitchat/widget/{uuid}/notifications/flush", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(3));
    }

    /** Like config(): a disabled widget is no widget, and its owner's deliveries stay pending. */
    @Test
    void flushNotifications_disabledWidget_returns404_withoutFlushing() throws Exception {
        UUID token = UUID.randomUUID();
        UserAccount owner = new UserAccount();
        owner.setId(UUID.randomUUID());
        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(owner));
        TwitchatWidgetSettings settings = new TwitchatWidgetSettings();
        settings.setEnabled(false);
        when(widgetSettingsRepository.findById(owner.getId())).thenReturn(Optional.of(settings));

        mvc.perform(post("/api/twitchat/widget/{uuid}/notifications/flush", token))
                .andExpect(status().isNotFound());
        org.mockito.Mockito.verifyNoInteractions(deliveryDispatcher);
    }

    @Test
    void flushNotifications_unknownToken_returns404() throws Exception {
        UUID token = UUID.randomUUID();
        when(widgetTokenService.resolve(token)).thenReturn(Optional.empty());

        mvc.perform(post("/api/twitchat/widget/{uuid}/notifications/flush", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void access_unknownToken_returns404() throws Exception {
        UUID token = UUID.randomUUID();
        when(widgetTokenService.resolve(token)).thenReturn(Optional.empty());

        mvc.perform(get("/api/twitchat/widget/{uuid}/access", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void access_knownToken_returnsOwnerAndEnabled() throws Exception {
        UUID token = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UserAccount owner = new UserAccount();
        owner.setId(ownerId);
        owner.setWidgetToken(token);
        TwitchatWidgetSettings settings = new TwitchatWidgetSettings();
        settings.setUser(owner);
        settings.setEnabled(true);
        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(owner));
        when(widgetSettingsRepository.findById(ownerId)).thenReturn(Optional.of(settings));

        mvc.perform(get("/api/twitchat/widget/{uuid}/access", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(ownerId.toString()))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void config_knownToken_decryptsPassword() throws Exception {
        UUID token = UUID.randomUUID();
        UserAccount owner = new UserAccount();
        owner.setId(UUID.randomUUID());
        owner.setWidgetToken(token);
        TwitchatWidgetSettings settings = new TwitchatWidgetSettings();
        settings.setUser(owner);
        settings.setEnabled(true);
        settings.setObsHost("127.0.0.1");
        settings.setObsPort(4455);
        settings.setObsPasswordEncrypted("ENC(pw)");
        settings.setTwitchatBranch("beta");
        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(owner));
        when(widgetSettingsRepository.findById(owner.getId())).thenReturn(Optional.of(settings));
        when(tokenEncryptionService.decrypt("ENC(pw)")).thenReturn("pw");

        mvc.perform(get("/api/twitchat/widget/{uuid}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.obsHost").value("127.0.0.1"))
                .andExpect(jsonPath("$.obsPort").value(4455))
                .andExpect(jsonPath("$.obsPassword").value("pw"))
                .andExpect(jsonPath("$.twitchatBranch").value("beta"));
    }

    @Test
    void config_disabledWidget_returns404() throws Exception {
        UUID token = UUID.randomUUID();
        UserAccount owner = new UserAccount();
        owner.setId(UUID.randomUUID());
        owner.setWidgetToken(token);
        TwitchatWidgetSettings settings = new TwitchatWidgetSettings();
        settings.setUser(owner);
        settings.setEnabled(false);
        settings.setObsHost("127.0.0.1");
        settings.setObsPort(4455);
        settings.setObsPasswordEncrypted("ENC(pw)");
        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(owner));
        when(widgetSettingsRepository.findById(owner.getId())).thenReturn(Optional.of(settings));

        mvc.perform(get("/api/twitchat/widget/{uuid}", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void action_executed_returnsExecutedResult() throws Exception {
        UUID token = UUID.randomUUID();
        when(actionExecutor.execute(token)).thenReturn(TwitchatActionExecutor.Result.EXECUTED);

        mvc.perform(post("/api/twitchat/actions/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("EXECUTED"));
    }
}
