package fr.enimaloc.catapult.security;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.api.ApiPaths;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authorization conventions: access rules live in {@link Authz}, admin routes sit under /api/admin or
 * /api/internal/admin, and every channel or user route asks {@link Authz} before its body runs.
 */
class AuthorizationConventionsTest {

  /** One {@link Authz} call whose arguments are method parameters, and nothing else: no {@code or}, no second rule. */
  private static final Pattern AUTHZ_CALL = Pattern.compile("^@authz\\.\\w+\\((#\\w+(\\s*,\\s*#\\w+)*)?\\)$");
  /** A rule that decides on the route's owner, the {@code userId} path variable. */
  private static final Pattern NAMES_USER_ID = Pattern.compile("#userId\\b");
  private static final String USERS = ApiPaths.INTERNAL + "/users/{";
  /** The access probe: it answers {@code false} rather than refusing (see its javadoc), so it has no rule. */
  private static final String CHANNEL_ACCESS_PROBE = ApiPaths.USER + "/channel-access";

  @ParameterizedTest
  @ValueSource(strings = {"@authz.ownsChannel(#username)", "@authz.canAccessChannel(#username)", "@authz.isAdmin()",
      "@authz.isSelf(#userId)", "@authz.canAccessChannel(#userId)",
      "@authz.check(#a, #b)"})
  void aBareAuthzCallIsAccepted(String expression) {
    assertThat(AUTHZ_CALL.matcher(expression).matches()).as(expression).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"@authz.ownsChannel(#username) or isAuthenticated()",
      "@authz.ownsChannel(#username) or hasIpAddress('::1')", "isAuthenticated() or @authz.ownsChannel(#username)",
      "@authz.ownsChannel('matrix-owner')", "@authz.ownsChannel(#username) and @authz.isAdmin()",
      "@authz.ownsChannel(#username.toString())", "permitAll()"})
  void anythingButABareAuthzCallIsRejected(String expression) {
    assertThat(AUTHZ_CALL.matcher(expression).matches()).as(expression).isFalse();
  }

  @Test
  void preAuthorizeOnlyAsksAuthz() throws ClassNotFoundException {
    List<String> violations = new ArrayList<>();
    for (Class<?> type : scan(org.springframework.stereotype.Component.class)) {
      check(type, AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class), violations);
      for (Method method : type.getDeclaredMethods()) {
        check(
            type.getName() + "#" + method.getName(),
            method.getAnnotation(PreAuthorize.class),
            violations);
      }
    }
    assertThat(violations).isEmpty();
  }

  @Test
  void adminControllersAreUnderApiAdmin() throws ClassNotFoundException {
    List<String> violations = new ArrayList<>();
    for (Class<?> type : scan(RestController.class)) {
      String pkg = type.getPackageName();
      if (!pkg.equals("fr.enimaloc.catapult.api.admin")
          && !pkg.equals("fr.enimaloc.catapult.api.provider")) {
        continue;
      }
      RequestMapping mapping = type.getAnnotation(RequestMapping.class);
      String[] paths = mapping == null ? new String[0] : mapping.path();
      if (paths.length == 0) {
        paths = mapping == null ? paths : mapping.value();
      }
      if (paths.length == 0) {
        violations.add(type.getName() + " has no class-level @RequestMapping path");
      }
      for (String path : paths) {
        if (!path.startsWith("/api/admin/") && !path.startsWith(ApiPaths.ADMIN + "/")) {
          violations.add(type.getName() + " maps " + path);
        }
      }
    }
    assertThat(violations).isEmpty();
  }

  /**
   * Every handler under {@code /api/internal/users/{...}} carries its own {@code @PreAuthorize}, which decides on
   * {@code #userId}. A class-level one
   * is not enough and is refused: it would also guard the controller's {@code @ExceptionHandler}s.
   */
  @Test
  void channelRoutesAreAuthorized() throws ClassNotFoundException {
    List<String> handlers = new ArrayList<>();
    List<String> violations = new ArrayList<>();
    for (Class<?> type : scan(org.springframework.stereotype.Controller.class)) {
      boolean classRule = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class) != null;
      boolean channelController = false;
      for (Method method : type.getDeclaredMethods()) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping == null || method.isAnnotationPresent(ExceptionHandler.class)) {
          continue;
        }
        List<String> paths = paths(type, mapping);
        boolean user = paths.stream().anyMatch(p -> p.startsWith(USERS));
        if (!user || isChannelAccessProbe(mapping, paths)) {
          continue;
        }
        channelController = true;
        String name = type.getName() + "#" + method.getName();
        handlers.add(name);
        PreAuthorize rule = method.getAnnotation(PreAuthorize.class);
        if (rule == null) {
          violations.add(name + " has no @PreAuthorize");
        } else if (!NAMES_USER_ID.matcher(rule.value()).find()) {
          violations.add(name + " @PreAuthorize(" + rule.value() + ") does not decide on #userId");
        }
      }
      if (channelController && classRule) {
        violations.add(type.getName() + " has a class-level @PreAuthorize, which also guards its @ExceptionHandler");
      }
    }
    assertThat(handlers).as("handlers under /api/internal/users/{...}").isNotEmpty();
    assertThat(violations).isEmpty();
  }

  private static boolean isChannelAccessProbe(RequestMapping mapping, List<String> paths) {
    return List.of(mapping.method()).equals(List.of(RequestMethod.GET)) && paths.equals(List.of(CHANNEL_ACCESS_PROBE));
  }

  /** {@code mapping}'s paths, each joined to {@code type}'s own mapping as Spring joins them. */
  private static List<String> paths(Class<?> type, RequestMapping mapping) {
    RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
    List<String> prefixes = classMapping == null || classMapping.path().length == 0
        ? List.of("")
        : List.of(classMapping.path());
    List<String> suffixes = mapping.path().length == 0 ? List.of("") : List.of(mapping.path());
    List<String> paths = new ArrayList<>();
    for (String prefix : prefixes) {
      for (String suffix : suffixes) {
        paths.add(join(prefix, suffix));
      }
    }
    return paths;
  }

  /** {@code prefix} then {@code suffix}, each led by exactly one slash, as Spring combines a class and method path. */
  static String join(String prefix, String suffix) {
    return slashed(prefix) + slashed(suffix);
  }

  private static String slashed(String path) {
    String trimmed = path.replaceAll("/+$", "");
    return trimmed.isEmpty() || trimmed.startsWith("/") ? trimmed : "/" + trimmed;
  }

  @ParameterizedTest
  @CsvSource({
      "/api/internal/users/{userId},/logs,/api/internal/users/{userId}/logs",
      "/api/internal/users/{userId},logs,/api/internal/users/{userId}/logs",
      "api/internal/users/{userId}/,logs,/api/internal/users/{userId}/logs",
      "'',/api/internal/users/{userId},/api/internal/users/{userId}",
      "/api/internal/users/{userId},'',/api/internal/users/{userId}"
  })
  void pathsJoinAsSpringJoinsThem(String prefix, String suffix, String joined) {
    assertThat(join(prefix, suffix)).isEqualTo(joined);
  }

  private static void check(Object where, PreAuthorize annotation, List<String> out) {
    if (annotation != null && !AUTHZ_CALL.matcher(annotation.value()).matches()) {
      out.add(
          (where instanceof Class<?> c ? c.getName() : where) + " @PreAuthorize(" + annotation.value() + ")");
    }
  }

  private static List<Class<?>> scan(Class<? extends java.lang.annotation.Annotation> marker)
      throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(marker));
    List<Class<?>> types = new ArrayList<>();
    for (BeanDefinition definition : scanner.findCandidateComponents("fr.enimaloc.catapult")) {
      types.add(Class.forName(definition.getBeanClassName()));
    }
    return types;
  }
}
