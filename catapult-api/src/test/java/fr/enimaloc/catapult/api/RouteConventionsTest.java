package fr.enimaloc.catapult.api;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.FullContextContainers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The conventions of the internal routes (see {@link ApiPaths}): every catapult route is under
 * {@value ApiPaths#INTERNAL} or one of the frozen external ones; an internal
 * route says what it does with its verb, not a path segment; and a route under {@value ApiPaths#USER} takes its owner
 * as a {@code UUID userId}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.config.additional-location=file:./src/main/resources/")
// The optional integrations on, as in the access matrix, so their routes are mapped too
@TestPropertySource(
        locations = "classpath:full-context.properties",
        properties = {
            "xbox.enabled=true",
            "xbox.client-id=full-context-test",
            "xbox.client-secret=full-context-test",
            "minecraft.enabled=true",
            "minecraft.msa-client-id=full-context-test",
            "dtdd.enabled=true"
        })
@Import(FullContextContainers.class)
@DirtiesContext
class RouteConventionsTest {

    /** The external routes, frozen byte for byte (as {@code ExternalRouteSetTest} pins them). */
    private static final Set<String> FROZEN = Set.of(
            "GET /api/connect/steam/callback",
            "GET /api/v2/custom-bindings",
            "POST /api/v2/custom-bindings/{idOrName}/activate",
            "POST /api/v2/custom-bindings/deactivate",
            "GET /api/game/{uuid}",
            "GET /api/user/game/{uuid}",
            "GET /api/user/summary/{uuid}",
            "GET /api/user/igdb/{igdbId}",
            "GET /api/user/steam/{appId}",
            "GET /api/user/xbox/{productId}",
            "GET /api/user/dtdd/{dtddId}",
            "GET /api/user/catapult/{bindingId}",
            "GET /api/twitchat/widget/{uuid}/access",
            "GET /api/twitchat/widget/{uuid}",
            "POST /api/twitchat/widget/{uuid}/notifications/flush",
            "POST /api/twitchat/actions/{token}");

    /** Path segments that name a CRUD operation the verb should carry. */
    private static final Set<String> CRUD_SEGMENTS = Set.of(
            "delete", "add", "remove", "update", "unlink", "rename", "disconnect", "cancel-deletion", "delete-account");

    /** The {@code POST}s that end with a variable and still create: an item in a collection the path names. */
    private static final Set<String> POST_INTO_NAMED_COLLECTION = Set.of(
            "POST " + ApiPaths.ADMIN + "/data/{repo}",
            "POST " + ApiPaths.USER + "/chat-commands/presets/{presetKey}",
            "POST " + ApiPaths.ADMIN + "/invites/grants/{memberId}");

    /**
     * The access probe takes its {@code userId} as a {@code String}: it answers {@code false} for a malformed one
     * rather than a 400 (see its javadoc).
     */
    private static final String CHANNEL_ACCESS_PROBE = "GET " + ApiPaths.USER + "/channel-access";

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyRouteIsInternalOrFrozen() {
        List<String> stray = new ArrayList<>();
        for (Route route : routes()) {
            if (!route.pattern().startsWith(ApiPaths.INTERNAL + "/")
                    && !FROZEN.contains(route.key())) {
                stray.add(route.key() + " (" + route.handler().getShortLogMessage() + ")");
            }
        }
        stray.sort(null);
        assertThat(stray).as("routes neither under " + ApiPaths.INTERNAL + "/ nor frozen").isEmpty();
    }

    @Test
    void internalRoutesUseRestVerbs() {
        List<String> violations = new ArrayList<>();
        for (Route route : routes()) {
            if (route.pattern().startsWith(ApiPaths.INTERNAL + "/")) {
                String violation = restViolation(route.method(), route.pattern());
                if (violation != null) {
                    violations.add(route.key() + ": " + violation);
                }
            }
        }
        violations.sort(null);
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "POST /api/internal/users/{userId}/bindings/{bindingId}/delete",
        "POST /api/internal/admin/whitelist/add",
        "POST /api/internal/admin/groups/{id}/rename",
        "POST /api/internal/users/{userId}/bindings/{bindingId}/ccl-toggle",
        "POST /api/internal/users/{userId}/settings/cancel-deletion",
        "POST /api/internal/users/{userId}/bindings/{bindingId}",
        "GET /api/internal/channels/{username}/settings"
    })
    void theRestRulesCatchWhatTheyName(String route) {
        String[] parts = route.split(" ");
        assertThat(restViolation(parts[0], parts[1])).as(route).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DELETE /api/internal/users/{userId}/bindings/{bindingId}",
        "PATCH /api/internal/users/{userId}/bindings/{bindingId}",
        "POST /api/internal/users/{userId}/settings/bot/toggle",
        "POST /api/internal/admin/experiments/{id}/activate",
        "POST /api/internal/admin/data/{repo}",
        "POST /api/internal/users/{userId}/chat-commands/presets/{presetKey}",
        "PUT /api/internal/users/{userId}/seen-features/{key}"
    })
    void theRestRulesLetRestRoutesThrough(String route) {
        String[] parts = route.split(" ");
        assertThat(restViolation(parts[0], parts[1])).as(route).isNull();
    }

    /** Why {@code method pattern} is not a REST route, or {@code null} when it is. */
    static String restViolation(String method, String pattern) {
        String[] segments = pattern.split("/");
        for (String segment : segments) {
            if (CRUD_SEGMENTS.contains(segment)) {
                return "the segment '" + segment + "' is the verb's job";
            }
            if (segment.endsWith("-toggle")) {
                return "the segment '" + segment + "' is a toggle: name it .../toggle";
            }
            if (segment.equals("{username}")) {
                return "a channel is addressed by its account id, not its {username}";
            }
        }
        String last = segments[segments.length - 1];
        if ("POST".equals(method) && last.startsWith("{")
                && !POST_INTO_NAMED_COLLECTION.contains(method + " " + pattern)) {
            return "a POST on an item: PUT or PATCH it, or POST an action under it";
        }
        return null;
    }

    @Test
    void userRoutesTakeTheirOwnerAsAUuid() {
        List<String> violations = new ArrayList<>();
        for (Route route : routes()) {
            String pattern = route.pattern();
            if (!pattern.startsWith(ApiPaths.INTERNAL + "/users/{")) {
                continue;
            }
            if (!pattern.equals(ApiPaths.USER) && !pattern.startsWith(ApiPaths.USER + "/")) {
                violations.add(route.key() + " names its owner other than {userId}");
            } else if (!bindsUserId(route.handler()) && !route.key().equals(CHANNEL_ACCESS_PROBE)) {
                violations.add(route.key() + " (" + route.handler().getShortLogMessage()
                        + ") has no @PathVariable UUID userId");
            }
        }
        violations.sort(null);
        assertThat(violations).isEmpty();
    }

    private static boolean bindsUserId(HandlerMethod handler) {
        for (MethodParameter parameter : handler.getMethodParameters()) {
            PathVariable variable = parameter.getParameterAnnotation(PathVariable.class);
            if (variable == null || parameter.getParameterType() != UUID.class) {
                continue;
            }
            parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
            String name = !variable.name().isEmpty() ? variable.name() : parameter.getParameterName();
            if ("userId".equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Every (method, pattern) of a catapult handler; a mapping with no method counts as {@code ANY}. */
    private List<Route> routes() {
        List<Route> routes = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (!entry.getValue().getBeanType().getName().startsWith("fr.enimaloc.catapult.")) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            List<String> methods = info.getMethodsCondition().getMethods().isEmpty()
                    ? List.of("ANY")
                    : info.getMethodsCondition().getMethods().stream().map(Enum::name).toList();
            for (String pattern : info.getPatternValues()) {
                for (String method : methods) {
                    routes.add(new Route(method, pattern, entry.getValue()));
                }
            }
        }
        return routes;
    }

    private record Route(String method, String pattern, HandlerMethod handler) {
        String key() {
            return method + " " + pattern;
        }
    }
}
