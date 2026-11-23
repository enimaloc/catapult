package fr.enimaloc.catapult.api.error;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The API versions a route is served in, for a request no handler took: a versioned route asked in another version
 * finds no handler and falls through to the static resources, whose 404 says nothing of the version. Read from the
 * request mappings on demand (a 404 is rare), and lazily: the advice must not pull the handler mapping in early.
 * Built by {@link ProblemAdvice}, not a bean of its own: the MVC test slices load the advice, not the components.
 */
public class RouteVersions {

    private final ObjectProvider<RequestMappingHandlerMapping> mapping;

    public RouteVersions(ObjectProvider<RequestMappingHandlerMapping> mapping) {
        this.mapping = mapping;
    }

    /**
     * The versions ({@code "3"}, {@code "2"}...; a baseline {@code "3+"} reads {@code "3"}) of the routes matching
     * this path and method, in order. Empty when nothing matches, or when a matching route is not versioned: then the
     * version is not why nothing answered.
     */
    public List<String> of(String path, String method) {
        RequestMappingHandlerMapping handlers = mapping.getIfAvailable();
        if (handlers == null) {
            return List.of();
        }
        PathContainer container = PathContainer.parsePath(path);
        TreeSet<String> versions = new TreeSet<>(Comparator.comparingInt(RouteVersions::lowest)
                .thenComparing(Comparator.naturalOrder()));
        for (Map.Entry<RequestMappingInfo, ?> entry : handlers.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            if (!matchesPath(info, container) || !matchesMethod(info, method)) {
                continue;
            }
            String version = info.getVersionCondition().getVersion();
            if (version == null) {
                return List.of();
            }
            versions.add(version);
        }
        return List.copyOf(versions);
    }

    /** The major version to ask for: {@code "3"}, {@code "3+"} and {@code "3.1"} all give {@code 3}. */
    static int lowest(String version) {
        String digits = version.endsWith("+") ? version.substring(0, version.length() - 1) : version;
        int dot = digits.indexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? digits : digits.substring(0, dot));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static boolean matchesPath(RequestMappingInfo info, PathContainer path) {
        var patterns = info.getPathPatternsCondition();
        return patterns != null && patterns.getPatterns().stream().anyMatch(pattern -> pattern.matches(path));
    }

    private static boolean matchesMethod(RequestMappingInfo info, String method) {
        var methods = info.getMethodsCondition().getMethods();
        return methods.isEmpty() || methods.stream().map(RequestMethod::name).anyMatch(method::equalsIgnoreCase);
    }
}
