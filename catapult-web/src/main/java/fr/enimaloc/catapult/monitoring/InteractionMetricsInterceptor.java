package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.controller.AdminMembersController;
import fr.enimaloc.catapult.controller.ChannelActionsController;
import fr.enimaloc.catapult.controller.IndexController;
import fr.enimaloc.catapult.controller.SessionChannels;
import fr.enimaloc.catapult.controller.SiteCatalog;
import fr.enimaloc.catapult.controller.SpaFragmentController;
import fr.enimaloc.catapult.controller.TwitchatEventsController;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Records each page shown and each dashboard action into {@link InteractionMetrics}, once
 * answered. A request is a page when {@link IndexController} or {@link SpaFragmentController}
 * serves it (the navbar's channels aside: spa.js loads them on its own), an action when one of the
 * mutation controllers does.
 */
@RequiredArgsConstructor
public class InteractionMetricsInterceptor implements HandlerInterceptor {
    private static final String START = InteractionMetricsInterceptor.class.getName() + ".start";
    private static final Map<Class<?>, String> ACTION_AREAS = Map.of(
            ChannelActionsController.class, "channel",
            TwitchatEventsController.class, "twitchat",
            AdminMembersController.class, "admin");
    /** Pages named by their route rather than by SiteCatalog. */
    private static final Set<String> ROUTED_PAGES = Set.of("channel", "admin", "landing");

    private final InteractionMetrics metrics;
    private final ApiService apiService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        if (!(handler instanceof HandlerMethod method) || !(request.getAttribute(START) instanceof Long start)) {
            return;
        }
        Class<?> controller = method.getBeanType();
        int status = ex != null ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
        String role = role(request);
        String area = ACTION_AREAS.get(controller);
        if (area != null) {
            metrics.action(area, method.getMethod().getName(), role, status, Duration.ofNanos(System.nanoTime() - start));
        } else if (controller == IndexController.class
                || controller == SpaFragmentController.class && !method.getMethod().getName().equals("navChannels")) {
            String path = request.getRequestURI();
            boolean spa = path.startsWith("/spa/");
            metrics.pageView(page(spa ? path.substring("/spa".length()) : path), spa ? "spa" : "full", role, status);
        }
    }

    /** The page {@code path} shows: a SiteCatalog page or a routed one, else {@code unknown}. */
    static String page(String path) {
        String segment = path.replaceAll("^/+", "").split("/", 2)[0];
        if (segment.isEmpty()) return "landing";
        return SiteCatalog.isPage(segment) || ROUTED_PAGES.contains(segment) ? segment : "unknown";
    }

    /**
     * {@code anonymous} when logged out; on a channel's request, its {@code owner} or a
     * {@code moderator} (from the channels the session last listed, {@code member} when it lists
     * none such yet); elsewhere {@code admin} or {@code member}.
     */
    String role(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !ApiClient.hasSession()) {
            return "anonymous";
        }
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables
                && variables.get("username") instanceof String username) {
            UUID viewer = apiService.currentUserId();
            return SessionChannels.find(session, username)
                    .map(channel -> channel.id().equals(viewer) ? "owner" : "moderator")
                    .orElse("member");
        }
        return apiService.isAdmin() ? "admin" : "member";
    }
}
