package fr.enimaloc.catapult.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authorization conventions: access rules live in {@link Authz}, admin routes sit under /api/admin, and every channel
 * route asks {@link Authz} before its body runs.
 */
class AuthorizationConventionsTest {

  /** One {@link Authz} call whose arguments are method parameters, and nothing else: no {@code or}, no second rule. */
  private static final Pattern AUTHZ_CALL = Pattern.compile("^@authz\\.\\w+\\((#\\w+(\\s*,\\s*#\\w+)*)?\\)$");

  @ParameterizedTest
  @ValueSource(strings = {"@authz.ownsChannel(#username)", "@authz.canAccessChannel(#username)", "@authz.isAdmin()",
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
        if (!path.startsWith("/api/admin/")) {
          violations.add(type.getName() + " maps " + path);
        }
      }
    }
    assertThat(violations).isEmpty();
  }

  @Test
  void channelRoutesAreAuthorized() throws ClassNotFoundException {
    List<String> handlers = new ArrayList<>();
    List<String> violations = new ArrayList<>();
    for (Class<?> type : scan(org.springframework.stereotype.Controller.class)) {
      boolean classRule = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class) != null;
      for (Method method : type.getDeclaredMethods()) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping == null
            || method.isAnnotationPresent(ExceptionHandler.class)
            || !underChannel(type, mapping)) {
          continue;
        }
        String name = type.getName() + "#" + method.getName();
        handlers.add(name);
        if (!classRule && method.getAnnotation(PreAuthorize.class) == null) {
          violations.add(name + " has no @PreAuthorize");
        }
      }
    }
    assertThat(handlers).as("handlers under /api/channels/{username}").isNotEmpty();
    assertThat(violations).isEmpty();
  }

  /** {@code mapping}, joined to {@code type}'s own mapping, is under {@code /api/channels/{username}}. */
  private static boolean underChannel(Class<?> type, RequestMapping mapping) {
    RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
    List<String> prefixes = classMapping == null || classMapping.path().length == 0
        ? List.of("")
        : List.of(classMapping.path());
    List<String> suffixes = mapping.path().length == 0 ? List.of("") : List.of(mapping.path());
    for (String prefix : prefixes) {
      for (String suffix : suffixes) {
        String path = prefix + suffix;
        if (path.equals("/api/channels/{username}") || path.startsWith("/api/channels/{username}/")) {
          return true;
        }
      }
    }
    return false;
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
