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
 * {@value ApiPaths#INTERNAL}, one of the frozen external ones, or a {@link #LEGACY} route not moved yet; an internal
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

    /**
     * The internal routes still at their old path, by the task that moves them: each slice deletes its lines, and the
     * list is gone once the last one has moved.
     */
    static final Set<String> LEGACY = Set.of(
            // Task 10
            "GET /api/users/{ownerId}/channel-access",
            "POST /api/channels/{username}/bindings/{id}/ccl-toggle",
            "POST /api/channels/{username}/bindings/{id}/ignored-toggle",
            "POST /api/channels/{username}/bindings/{id}/delete",
            "POST /api/channels/{username}/bindings/{id}",
            "POST /api/channels/{username}/settings/bot",
            "GET /api/channels/{username}/settings/twitchat",
            "POST /api/channels/{username}/settings/twitchat",
            "POST /api/channels/{username}/settings/obs",
            "POST /api/channels/{username}/settings/twitchat/branch",
            "POST /api/channels/{username}/settings/twitchat/regenerate",
            "GET /api/channels/{username}/twitchat/events",
            "PUT /api/channels/{username}/twitchat/events/{eventType}/actions",
            "POST /api/channels/{username}/twitchat/events/{eventType}/test",
            "POST /api/channels/{username}/game/recheck",
            "POST /api/channels/{username}/settings/ccl",
            "POST /api/channels/{username}/settings/tws",
            "POST /api/channels/{username}/settings/no-game",
            "POST /api/channels/{username}/settings/incomplete-fallback",
            "POST /api/channels/{username}/settings/raid",
            "POST /api/channels/{username}/settings/steam-personal-token",
            "POST /api/channels/{username}/settings/steam-personal-token/sharing",
            "POST /api/channels/{username}/settings/steam-personal-token/delete",
            "POST /api/channels/{username}/settings/delete-account",
            "POST /api/channels/{username}/settings/cancel-deletion",
            "POST /api/channels/{username}/settings/disconnect",
            "GET /api/channels/{username}/custom-bindings",
            "POST /api/channels/{username}/custom-bindings",
            "POST /api/channels/{username}/custom-bindings/{id}",
            "POST /api/channels/{username}/custom-bindings/{id}/delete",
            "POST /api/channels/{username}/custom-bindings/{id}/activate",
            "POST /api/channels/{username}/custom-bindings/deactivate",
            "GET /api/channels/{username}/logs",
            "GET /api/channels/{username}/connections",
            "GET /api/channels/{username}",
            "POST /api/channels/{username}/steam/refresh-profile-cache",
            "GET /api/channels/{username}/status",
            "GET /api/channels/{username}/settings",
            "GET /api/channels/{username}/games/search",
            "GET /api/channels/{username}/bindings/current",
            "GET /api/channels/{username}/dtdd-mapping",
            "POST /api/channel/bindings/{bindingId}/tws",
            "POST /api/channel/bindings/{bindingId}/tws/reset",
            "POST /api/channel/bindings/{bindingId}/tw-enabled",
            "GET /api/channel/bindings/{bindingId}/tws/suggest",
            // Task 11
            "POST /api/admin/broadcast",
            "GET /api/admin/experiments",
            "GET /api/admin/experiments/{id}/settings",
            "GET /api/admin/experiments/{id}",
            "POST /api/admin/experiments/{id}/activate",
            "POST /api/admin/experiments/{id}/pause",
            "POST /api/admin/experiments/{id}/end",
            "POST /api/admin/experiments/{id}/assign",
            "POST /api/admin/experiments/{id}/variants/weights",
            "POST /api/admin/experiments/{id}/variants/weights/preview",
            "POST /api/admin/experiments/{id}/rules",
            "POST /api/admin/experiments/{id}/rules/{ruleId}/delete",
            "POST /api/admin/experiments/{id}/overrides",
            "POST /api/admin/experiments/{id}/overrides/{overrideId}/delete",
            "GET /api/admin/experiments/{id}/assignments",
            "POST /api/admin/experiments/{id}/assignments/{userId}/variant",
            "POST /api/admin/experiments/{id}/reassign",
            "GET /api/admin/flags",
            "GET /api/admin/groups",
            "POST /api/admin/groups",
            "POST /api/admin/groups/{id}/rename",
            "POST /api/admin/groups/{id}/delete",
            "POST /api/admin/groups/{id}/members",
            "POST /api/admin/groups/{id}/members/{userId}/delete",
            "POST /api/admin/impersonate",
            "GET /api/admin/invite",
            "POST /api/admin/invite/settings",
            "POST /api/admin/invite/{inviteId}/quota",
            "POST /api/admin/invite/grant/{userId}",
            "POST /api/admin/members/{id}/debug/enable",
            "POST /api/admin/members/{id}/debug/disable",
            "POST /api/admin/members/{id}/debug/clear",
            "GET /api/admin/members/{id}/debug",
            "GET /api/admin/members/{id}/debug/exchanges",
            "GET /api/admin/members/{id}/debug/exchanges/{exchangeId}",
            "GET /api/admin/members/{id}/debug/services",
            "GET /api/admin/members",
            "GET /api/admin/members/search",
            "POST /api/admin/members/{id}/bot/toggle",
            "POST /api/admin/members/{id}/delete",
            "POST /api/admin/members/{id}/steam/unlink",
            "POST /api/admin/members/{id}/twitch/unlink",
            "POST /api/admin/members/{id}/migrate",
            "POST /api/admin/members/{id}/promote-to-system",
            "GET /api/admin/members/{id}",
            "POST /api/admin/members/{id}/flags",
            "POST /api/admin/members/{id}/flags/{key}/delete",
            "POST /api/admin/members/{id}/groups",
            "POST /api/admin/members/{id}/groups/{groupId}/delete",
            "GET /api/admin/members/{id}/targeting",
            "POST /api/admin/notifications",
            "POST /api/admin/notifications/audience-count",
            "GET /api/admin/notifications",
            "POST /api/admin/notifications/preview",
            "DELETE /api/admin/notifications/{id}",
            "GET /api/admin/whitelist",
            "POST /api/admin/whitelist/toggle",
            "POST /api/admin/whitelist/add",
            "POST /api/admin/whitelist/{id}/delete",
            "POST /api/admin/whitelist/invite/toggle",
            "POST /api/admin/whitelist/invite/settings",
            "POST /api/admin/whitelist/invite/{inviteId}/quota",
            // Task 12
            "GET /api/admin/caches",
            "GET /api/admin/caches/{name}",
            "GET /api/admin/caches/{name}/entry",
            "DELETE /api/admin/caches/{name}",
            "GET /api/admin/ccl",
            "POST /api/admin/ccl/refresh",
            "POST /api/admin/ccl/{cclId}/mappings",
            "GET /api/admin/config",
            "PUT /api/admin/config/{key}",
            "DELETE /api/admin/config/{key}",
            "GET /api/admin/config/audit",
            "GET /api/admin/config/module/{module}",
            "PUT /api/admin/config/module/{module}/{key}",
            "DELETE /api/admin/config/module/{module}/{key}",
            "GET /api/admin/config/module/{module}/audit",
            "GET /api/admin/data",
            "GET /api/admin/data/{repo}",
            "GET /api/admin/data/{repo}/{id}",
            "POST /api/admin/data/{repo}",
            "PUT /api/admin/data/{repo}/{id}",
            "DELETE /api/admin/data/{repo}/{id}",
            "GET /api/admin/dtdd-keys",
            "POST /api/admin/dtdd-keys/add",
            "POST /api/admin/dtdd-keys/delete",
            "POST /api/admin/dtdd-keys/refresh",
            "GET /api/admin/dtdd-mapping/proposals",
            "POST /api/admin/dtdd-mapping/proposals/{id}/approve",
            "POST /api/admin/dtdd-mapping/proposals/{id}/reject",
            "GET /api/admin/game-mappings",
            "POST /api/admin/game-mappings",
            "PUT /api/admin/game-mappings/{id}",
            "DELETE /api/admin/game-mappings/{id}",
            "GET /api/admin/game-mappings/missing",
            "GET /api/admin/game-mappings/detected",
            "GET /api/admin/game-mappings/categories",
            "GET /api/admin/igdb/search",
            "POST /api/admin/meta-tags",
            "PUT /api/admin/meta-tags/{id}",
            "DELETE /api/admin/meta-tags/{id}",
            "GET /api/admin/minecraft-accounts",
            "POST /api/admin/minecraft-accounts/device-code",
            "POST /api/admin/minecraft-accounts",
            "POST /api/admin/minecraft-accounts/{id}/reauth",
            "PATCH /api/admin/minecraft-accounts/{id}",
            "DELETE /api/admin/minecraft-accounts/{id}",
            "GET /api/admin/steam-keys",
            "POST /api/admin/steam-keys/add",
            "POST /api/admin/steam-keys/delete",
            "POST /api/admin/steam-keys/refresh",
            "GET /api/admin/tw",
            "GET /api/admin/tw/{id}",
            "POST /api/admin/tw",
            "PATCH /api/admin/tw/{id}",
            "DELETE /api/admin/tw/{id}",
            "PUT /api/admin/tw/{id}/dtdd-topics",
            "PUT /api/admin/tw/{id}/igdb-descriptors",
            "PUT /api/admin/tw/{id}/steam-content-ids",
            "PUT /api/admin/tw/{id}/steam-keywords",
            "GET /api/admin/tw/{id}/steam-keywords",
            "POST /api/admin/tw/{id}/steam-keywords",
            "DELETE /api/admin/tw/{id}/steam-keywords/{keyword}",
            "POST /api/admin/tw/{id}/steam-keywords/test",
            "POST /api/admin/tw/rebuild",
            "POST /api/admin/tw/rebuild/force",
            "POST /api/admin/tw/rebuild/force-pinned",
            "GET /api/admin/well-known",
            "GET /api/admin/well-known/iana",
            "GET /api/admin/well-known/schema",
            "GET /api/admin/well-known/{id}",
            "POST /api/admin/well-known",
            "PUT /api/admin/well-known/{id}",
            "DELETE /api/admin/well-known/{id}",
            "GET /api/admin/providers/gitlab/issues/{iid}",
            "POST /api/admin/providers/igdb/query",
            "GET /api/admin/providers/minecraft/profile-lookup",
            "GET /api/admin/providers/minecraft/friends",
            "GET /api/admin/providers/minecraft/profile",
            "GET /api/admin/providers/minecraft/presence",
            "GET /api/admin/providers/steam/appdetails",
            "GET /api/admin/providers/twitch/games",
            "GET /api/admin/providers/twitch/users",
            "GET /api/admin/providers/twitch/moderated-channels",
            "GET /api/admin/providers/xbox/presence"
    );

    /** Path segments that name a CRUD operation the verb should carry. */
    private static final Set<String> CRUD_SEGMENTS = Set.of(
            "delete", "add", "remove", "update", "unlink", "rename", "disconnect", "cancel-deletion", "delete-account");

    /** The {@code POST}s that end with a variable and still create: an item in a collection the path names. */
    private static final Set<String> POST_INTO_NAMED_COLLECTION = Set.of(
            "POST " + ApiPaths.ADMIN + "/data/{repo}",
            "POST " + ApiPaths.USER + "/chat-commands/presets/{presetKey}");

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyRouteIsInternalFrozenOrLegacy() {
        List<String> stray = new ArrayList<>();
        for (Route route : routes()) {
            if (!route.pattern().startsWith(ApiPaths.INTERNAL + "/")
                    && !FROZEN.contains(route.key())
                    && !LEGACY.contains(route.key())) {
                stray.add(route.key() + " (" + route.handler().getShortLogMessage() + ")");
            }
        }
        stray.sort(null);
        assertThat(stray).as("routes neither under " + ApiPaths.INTERNAL + "/, frozen nor legacy").isEmpty();
    }

    @Test
    void internalRoutesUseRestVerbs() {
        List<String> violations = new ArrayList<>();
        for (Route route : routes()) {
            if (route.pattern().startsWith(ApiPaths.INTERNAL + "/") && !LEGACY.contains(route.key())) {
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
            } else if (!bindsUserId(route.handler())) {
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
