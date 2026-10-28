package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.experiment.LegacyApiRoutesExperiment;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gates the legacy public routes ({@link UserApiV1Controller}, {@link UserApiV2Controller}) behind the
 * {@value LegacyApiRoutesExperiment#KEY} experiment. An open route answers as it always did, plus
 * {@code Deprecation: true} and a {@code Link} to its successor; a closed one answers {@code 410 Gone} as a problem
 * naming that successor.
 *
 * <p>A route keyed by a widget token is open when the token's account is in the {@code legacy} variant. A dev
 * backdoor uuid or a route without a token is open while the experiment is ACTIVE: anonymous traffic never creates
 * an assignment. An unknown widget token is left to the controller, which answers its usual 404.
 *
 * <p>The legacy routes are frozen ({@code FrozenRoutes}), so {@code ProblemAdvice} never touches them: the 410 is
 * written here. It runs after Spring's CORS interceptor, so it carries the CORS headers of the route.
 */
public class LegacyRoutesGate implements HandlerInterceptor {

    static final String DEPRECATION_HEADER = "Deprecation";
    static final String LINK_HEADER = "Link";

    private static final Set<Class<?>> LEGACY_CONTROLLERS = Set.of(UserApiV1Controller.class, UserApiV2Controller.class);

    private static final String TOKEN_VARIABLE = "uuid";

    private record Successor(Pattern legacy, String replacement) {}

    private static final List<Successor> SUCCESSORS = List.of(
            new Successor(Pattern.compile("/api/game/[^/]+"), ApiV2.ME_GAME_PATH),
            new Successor(Pattern.compile(ApiV2.PATH + "/game/[^/]+"), ApiV2.ME_GAME_PATH),
            new Successor(Pattern.compile(ApiV2.PATH + "/summary/[^/]+"), ApiV2.ME_GAME_PATH + "/summary"),
            new Successor(Pattern.compile(ApiV2.PATH + "/catapult/([^/]+)"), ApiV2.ME_BINDINGS_PATH + "/$1"),
            new Successor(Pattern.compile(ApiV2.PATH + "/(igdb|steam|xbox|dtdd)/([^/]+)"),
                    ApiV2.GAMES_PATH + "/$1/$2"));

    private enum Verdict { OPEN, CLOSED, CONTROLLER }

    private final ExperimentService experimentService;
    private final WidgetTokenService widgetTokenService;
    private final PublicBaseUrl publicBaseUrl;
    private final JsonMapper json;

    /** {@code json} is the application's mapper: Boot registered the mixin that puts {@code code} at the top level. */
    public LegacyRoutesGate(ExperimentService experimentService, WidgetTokenService widgetTokenService,
            PublicBaseUrl publicBaseUrl, JsonMapper json) {
        this.experimentService = experimentService;
        this.widgetTokenService = widgetTokenService;
        this.publicBaseUrl = publicBaseUrl;
        this.json = json;
    }

    /** The current route that replaces the legacy route at {@code legacyPath}, or null if it is not a legacy route. */
    static @Nullable String successor(String legacyPath) {
        for (Successor successor : SUCCESSORS) {
            Matcher matcher = successor.legacy().matcher(legacyPath);
            if (matcher.matches()) {
                return matcher.replaceFirst(successor.replacement());
            }
        }
        return null;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method) || !LEGACY_CONTROLLERS.contains(method.getBeanType())) {
            return true;
        }
        String successor = successor(request.getRequestURI().substring(request.getContextPath().length()));
        if (successor == null) {
            return true;
        }
        Verdict verdict = verdict(request);
        if (verdict == Verdict.CONTROLLER) {
            return true;
        }
        String successorUrl = publicBaseUrl.get() + successor;
        response.setHeader(LINK_HEADER, "<" + successorUrl + ">; rel=\"successor-version\"");
        if (verdict == Verdict.OPEN) {
            response.setHeader(DEPRECATION_HEADER, "true");
            return true;
        }
        writeGone(response, successorUrl);
        return false;
    }

    private Verdict verdict(HttpServletRequest request) {
        String token = tokenOf(request);
        if (token == null) {
            return experimentActive();
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(token);
        } catch (IllegalArgumentException e) {
            // Not a uuid: the controller answers its usual 400
            return Verdict.CONTROLLER;
        }
        if (DevBackdoorResolver.isBackdoor(uuid)) {
            return experimentActive();
        }
        return widgetTokenService.resolve(uuid)
                .map(user -> experimentService.evaluateGate(user, LegacyApiRoutesExperiment.KEY)
                        ? Verdict.OPEN : Verdict.CLOSED)
                .orElse(Verdict.CONTROLLER);
    }

    private static @Nullable String tokenOf(HttpServletRequest request) {
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables
                && variables.get(TOKEN_VARIABLE) instanceof String token) {
            return token;
        }
        return null;
    }

    private Verdict experimentActive() {
        boolean active = experimentService.getExperiment(LegacyApiRoutesExperiment.KEY)
                .map(experiment -> experiment.getStatus() == Experiment.Status.ACTIVE)
                .orElse(false);
        return active ? Verdict.OPEN : Verdict.CLOSED;
    }

    private void writeGone(HttpServletResponse response, String successorUrl) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.GONE, "This route was removed; use " + successorUrl + " instead");
        problem.setTitle(HttpStatus.GONE.getReasonPhrase());
        problem.setProperty(ErrorCode.PROPERTY, ErrorCode.ROUTE_GONE.code());
        response.setStatus(HttpStatus.GONE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
