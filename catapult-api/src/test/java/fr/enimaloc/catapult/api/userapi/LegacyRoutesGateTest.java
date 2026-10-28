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
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
    void encodedPath_isGatedLikeThePlainOne() throws Exception {
        experiment(Experiment.Status.ACTIVE);
        UUID token = widgetToken(false);

        mvc.perform(get(URI.create("/api/user/%67ame/" + token)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("route_gone"))
                .andExpect(header().string("Link", "<" + BASE + "/api/me/game>; rel=\"successor-version\""));
        mvc.perform(get(URI.create("/api/user/st%65am/400")))
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(header().string("Link", "<" + BASE + "/api/games/steam/400>; rel=\"successor-version\""));

        verify(gameInfoAssembler, never()).gameInfo(any(UUID.class), any());
    }

    @Test
    void successor_mapsEveryLegacyRoute() {
        String id = UUID.randomUUID().toString();
        assertEquals("/api/me/game", LegacyRoutesGate.successor("/api/game/{uuid}", Map.of("uuid", id)));
        assertEquals("/api/me/game", LegacyRoutesGate.successor("/api/user/game/{uuid}", Map.of("uuid", id)));
        assertEquals("/api/me/game/summary",
                LegacyRoutesGate.successor("/api/user/summary/{uuid}", Map.of("uuid", id)));
        assertEquals("/api/me/bindings/" + id,
                LegacyRoutesGate.successor("/api/user/catapult/{bindingId}", Map.of("bindingId", id)));
        assertEquals("/api/games/igdb/1", LegacyRoutesGate.successor("/api/user/igdb/{igdbId}", Map.of("igdbId", "1")));
        assertEquals("/api/games/steam/400",
                LegacyRoutesGate.successor("/api/user/steam/{appId}", Map.of("appId", "400")));
        assertEquals("/api/games/xbox/9NBLGGH2JHXJ",
                LegacyRoutesGate.successor("/api/user/xbox/{productId}", Map.of("productId", "9NBLGGH2JHXJ")));
        assertEquals("/api/games/dtdd/16758",
                LegacyRoutesGate.successor("/api/user/dtdd/{dtddId}", Map.of("dtddId", "16758")));
        assertNull(LegacyRoutesGate.successor("/api/me/game", Map.of()));
    }

    @Test
    void successor_encodesTheIdItCarriesOver() {
        assertEquals("/api/games/xbox/a%2Fb%20c",
                LegacyRoutesGate.successor("/api/user/xbox/{productId}", Map.of("productId", "a/b c")));
    }

    /** Every route of the legacy controllers, as Spring combines its class and method mappings, has a successor. */
    @Test
    void everyLegacyRequestMapping_hasASuccessor() {
        List<String> patterns = new ArrayList<>();
        for (Class<?> controller : List.of(UserApiV1Controller.class, UserApiV2Controller.class)) {
            RequestMapping root = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String[] prefixes = root == null || root.path().length == 0 ? new String[] {""} : root.path();
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String[] paths = mapping.path().length == 0 ? new String[] {""} : mapping.path();
                for (String prefix : prefixes) {
                    for (String path : paths) {
                        patterns.add(prefix + path);
                    }
                }
            }
        }

        assertThat(patterns).hasSize(8).allSatisfy(pattern -> {
            Map<String, String> variables = new HashMap<>();
            Matcher variable = Pattern.compile("\\{(\\w+)}").matcher(pattern);
            while (variable.find()) {
                variables.put(variable.group(1), "x");
            }
            assertThat(LegacyRoutesGate.successor(pattern, variables)).as(pattern).isNotNull();
        });
    }

    /** A legacy handler the gate knows no successor of is closed, whatever the experiment says. */
    @Test
    void legacyHandlerWithoutSuccessor_failsClosed() throws Exception {
        experiment(Experiment.Status.ACTIVE);
        LegacyRoutesGate gate = new LegacyRoutesGate(experimentService, widgetTokenService,
                new PublicBaseUrl(BASE), JsonMapper.builder().build());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user/added/1");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/user/added/{id}");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "1"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        // A handler of the legacy controller, matched under a pattern the gate has no successor for
        HandlerMethod handler = new HandlerMethod(mock(UserApiV2Controller.class),
                UserApiV2Controller.class.getMethod("igdbDetail", String.class));

        assertThat(gate.preHandle(request, response, handler)).isFalse();
        assertThat(response.getStatus()).isEqualTo(410);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"code\":\"route_gone\"");
        assertThat(response.getHeader("Link")).isNull();
    }
}
