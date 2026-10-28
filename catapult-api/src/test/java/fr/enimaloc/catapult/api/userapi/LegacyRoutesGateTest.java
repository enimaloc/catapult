package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.experiment.LegacyApiRoutesExperiment;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {UserApiV1Controller.class, UserApiV2Controller.class},
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@AutoConfigureMockMvc(addFilters = false)
@Import({LegacyRoutesGateConfig.class, PublicBaseUrl.class})
class LegacyRoutesGateTest {

    private static final String BASE = "http://localhost";
    private static final UUID BACKDOOR = UUID.fromString(ApiV2.GENERIC_UUID_EXAMPLE);

    @Autowired MockMvc mvc;

    @MockitoBean UserApiTokenService userApiTokenService;
    @MockitoBean GameInfoAssembler gameInfoAssembler;
    @MockitoBean WidgetTokenService widgetTokenService;
    @MockitoBean ExperimentService experimentService;

    private void experiment(Experiment.Status status) {
        Experiment experiment = new Experiment();
        experiment.setKey(LegacyApiRoutesExperiment.KEY);
        experiment.setStatus(status);
        when(experimentService.getExperiment(LegacyApiRoutesExperiment.KEY)).thenReturn(Optional.of(experiment));
    }

    private UUID widgetToken(boolean legacyVariant) {
        UUID token = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(user));
        when(experimentService.evaluateGate(user, LegacyApiRoutesExperiment.KEY)).thenReturn(legacyVariant);
        when(gameInfoAssembler.gameInfo(any(UUID.class), any())).thenReturn(Optional.of(new GameInfoResponse(false)));
        return token;
    }

    @Test
    void closed_is410Problem_withRouteGoneCode_andLink() throws Exception {
        experiment(Experiment.Status.ACTIVE);
        UUID token = widgetToken(false);

        mvc.perform(get("/api/user/game/{uuid}", token))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.title").value("Gone"))
                .andExpect(jsonPath("$.code").value("route_gone"))
                .andExpect(jsonPath("$.detail").value(containsString(BASE + "/api/me/game")))
                .andExpect(header().string("Link", "<" + BASE + "/api/me/game>; rel=\"successor-version\""))
                .andExpect(header().doesNotExist("Deprecation"));

        mvc.perform(get("/api/game/{uuid}", token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("route_gone"))
                .andExpect(header().string("Link", "<" + BASE + "/api/me/game>; rel=\"successor-version\""));

        verify(gameInfoAssembler, never()).gameInfo(any(UUID.class), any());
        verify(gameInfoAssembler, never()).legacyGameInfo(any(UUID.class), any());
    }

    @Test
    void closed_keepsTheRoutesCorsHeaders() throws Exception {
        experiment(Experiment.Status.ACTIVE);
        UUID token = widgetToken(false);

        mvc.perform(get("/api/user/summary/{uuid}", token).header("Origin", "https://twitchat.fr"))
                .andExpect(status().isGone())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
        mvc.perform(get("/api/game/{uuid}", token).header("Origin", "https://twitchat.fr"))
                .andExpect(status().isGone())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
    }

    @Test
    void open_addsDeprecationAndLink_andKeepsBody() throws Exception {
        experiment(Experiment.Status.ACTIVE);
        UUID token = widgetToken(true);

        mvc.perform(get("/api/user/summary/{uuid}", token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.inGame").value(false))
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(header().string("Link",
                        "<" + BASE + "/api/me/game/summary>; rel=\"successor-version\""));
    }

    @Test
    void unknownWidgetToken_keepsTheControllers404() throws Exception {
        experiment(Experiment.Status.ACTIVE);
        UUID token = UUID.randomUUID();
        when(widgetTokenService.resolve(token)).thenReturn(Optional.empty());
        when(gameInfoAssembler.gameInfo(any(UUID.class), any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/user/game/{uuid}", token))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Link"));
        verify(experimentService, never()).evaluateGate(any(), anyString());
    }

    @Test
    void tokenlessRoute_followsExperimentActive() throws Exception {
        experiment(Experiment.Status.ACTIVE);
        UUID bindingId = UUID.randomUUID();
        when(gameInfoAssembler.catapult(any(UUID.class), any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/user/igdb/{id}", "42"))
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(header().string("Link", "<" + BASE + "/api/games/igdb/42>; rel=\"successor-version\""));
        mvc.perform(get("/api/user/catapult/{id}", bindingId))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(header().string("Link",
                        "<" + BASE + "/api/me/bindings/" + bindingId + ">; rel=\"successor-version\""));

        verify(experimentService, never()).evaluateGate(any(), anyString());
        verifyNoInteractions(widgetTokenService);
    }

    @Test
    void experimentNotActive_closesEverything() throws Exception {
        experiment(Experiment.Status.PAUSED);
        UUID token = widgetToken(false);

        mvc.perform(get("/api/user/game/{uuid}", token)).andExpect(status().isGone());
        mvc.perform(get("/api/user/game/{uuid}", BACKDOOR)).andExpect(status().isGone());
        mvc.perform(get("/api/user/steam/{id}", "400"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("route_gone"))
                .andExpect(header().string("Link", "<" + BASE + "/api/games/steam/400>; rel=\"successor-version\""));
    }

    @Test
    void missingExperiment_closesEverything() throws Exception {
        when(experimentService.getExperiment(LegacyApiRoutesExperiment.KEY)).thenReturn(Optional.empty());

        mvc.perform(get("/api/user/dtdd/{id}", "16758")).andExpect(status().isGone());
    }

    @Test
    void backdoorUuid_followsExperimentActive() throws Exception {
        experiment(Experiment.Status.ACTIVE);
        when(gameInfoAssembler.gameInfo(any(UUID.class), any())).thenReturn(Optional.of(new GameInfoResponse(false)));

        mvc.perform(get("/api/user/game/{uuid}", BACKDOOR))
                .andExpect(status().isOk())
                .andExpect(header().string("Deprecation", "true"));

        verifyNoInteractions(widgetTokenService);
        verify(experimentService, never()).evaluateGate(any(), anyString());
    }

    @Test
    void successor_mapsEveryLegacyRoute() {
        UUID id = UUID.randomUUID();
        assertEquals("/api/me/game", LegacyRoutesGate.successor("/api/game/" + id));
        assertEquals("/api/me/game", LegacyRoutesGate.successor("/api/user/game/" + id));
        assertEquals("/api/me/game/summary", LegacyRoutesGate.successor("/api/user/summary/" + id));
        assertEquals("/api/me/bindings/" + id, LegacyRoutesGate.successor("/api/user/catapult/" + id));
        assertEquals("/api/games/igdb/1", LegacyRoutesGate.successor("/api/user/igdb/1"));
        assertEquals("/api/games/steam/400", LegacyRoutesGate.successor("/api/user/steam/400"));
        assertEquals("/api/games/xbox/9NBLGGH2JHXJ", LegacyRoutesGate.successor("/api/user/xbox/9NBLGGH2JHXJ"));
        assertEquals("/api/games/dtdd/16758", LegacyRoutesGate.successor("/api/user/dtdd/16758"));
        assertNull(LegacyRoutesGate.successor("/api/me/game"));
    }
}
