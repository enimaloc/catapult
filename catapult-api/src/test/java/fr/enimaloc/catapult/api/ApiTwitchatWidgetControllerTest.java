package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.TwitchatWidgetSettings;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.TwitchatWidgetSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.notification.TwitchatActionExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
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
class ApiTwitchatWidgetControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean TwitchatWidgetSettingsRepository widgetSettingsRepository;
    @MockitoBean TokenEncryptionService tokenEncryptionService;
    @MockitoBean TwitchatActionExecutor actionExecutor;

    @Test
    void defaults_returnsAllFiveEventTypes() throws Exception {
        mvc.perform(get("/api/twitchat/defaults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.STREAM_STARTED.message").value("Le bot Catapult est actif."))
                .andExpect(jsonPath("$.STREAM_STARTED.icon").value("live"))
                .andExpect(jsonPath("$.STREAM_STARTED.actions", hasSize(1)))
                .andExpect(jsonPath("$.STREAM_STARTED.actions[0].label").value("Désactiver le bot"))
                .andExpect(jsonPath("$.STREAM_STARTED.actions[0].actionType").value("url"))
                .andExpect(jsonPath("$.STREAM_STARTED.actions[0].theme").value("alert"))
                .andExpect(jsonPath("$.STREAM_STARTED.actions[0].url")
                        .value("http://localhost:8081/widget/twitchat/action/{{action:DISABLE_BOT}}"))
                .andExpect(jsonPath("$.CATEGORY_CHANGED_BY_CATAPULT.actions", hasSize(2)));
    }

    @Test
    void access_unknownToken_returns404() throws Exception {
        UUID token = UUID.randomUUID();
        when(widgetSettingsRepository.findByWidgetToken(token)).thenReturn(Optional.empty());

        mvc.perform(get("/api/twitchat/widget/{token}/access", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void access_knownToken_returnsOwnerAndEnabled() throws Exception {
        UUID token = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UserAccount owner = new UserAccount();
        owner.setId(ownerId);
        TwitchatWidgetSettings settings = new TwitchatWidgetSettings();
        settings.setUser(owner);
        settings.setWidgetToken(token);
        settings.setEnabled(true);
        when(widgetSettingsRepository.findByWidgetToken(token)).thenReturn(Optional.of(settings));

        mvc.perform(get("/api/twitchat/widget/{token}/access", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(ownerId.toString()))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void config_knownToken_decryptsPassword() throws Exception {
        UUID token = UUID.randomUUID();
        UserAccount owner = new UserAccount();
        owner.setId(UUID.randomUUID());
        TwitchatWidgetSettings settings = new TwitchatWidgetSettings();
        settings.setUser(owner);
        settings.setWidgetToken(token);
        settings.setEnabled(true);
        settings.setObsHost("127.0.0.1");
        settings.setObsPort(4455);
        settings.setObsPasswordEncrypted("ENC(pw)");
        when(widgetSettingsRepository.findByWidgetToken(token)).thenReturn(Optional.of(settings));
        when(tokenEncryptionService.decrypt("ENC(pw)")).thenReturn("pw");

        mvc.perform(get("/api/twitchat/widget/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.obsHost").value("127.0.0.1"))
                .andExpect(jsonPath("$.obsPort").value(4455))
                .andExpect(jsonPath("$.obsPassword").value("pw"));
    }

    @Test
    void config_disabledWidget_returns404() throws Exception {
        UUID token = UUID.randomUUID();
        UserAccount owner = new UserAccount();
        owner.setId(UUID.randomUUID());
        TwitchatWidgetSettings settings = new TwitchatWidgetSettings();
        settings.setUser(owner);
        settings.setWidgetToken(token);
        settings.setEnabled(false);
        settings.setObsHost("127.0.0.1");
        settings.setObsPort(4455);
        settings.setObsPasswordEncrypted("ENC(pw)");
        when(widgetSettingsRepository.findByWidgetToken(token)).thenReturn(Optional.of(settings));

        mvc.perform(get("/api/twitchat/widget/{token}", token))
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

    @Test
    void quickConfigs_returnsCatalogWithBaseUrlResolved() throws Exception {
        mvc.perform(get("/api/twitchat/quick-configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("stream-started-disable-link"))
                .andExpect(jsonPath("$[0].templateJson").value(org.hamcrest.Matchers.containsString(
                        "http://localhost:8081/widget/twitchat/action/{{action:DISABLE_BOT}}")))
                .andExpect(jsonPath("$[1].key").value("stream-started-disable-chat"))
                .andExpect(jsonPath("$[1].parameters[0].key").value("command"))
                .andExpect(jsonPath("$[1].templateJson").value(org.hamcrest.Matchers.containsString(
                        "{{param:command}}")));
    }
}
