package fr.enimaloc.catapult.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authorization conventions: access rules live in {@link Authz}, admin routes sit under /api/admin. */
class AuthorizationConventionsTest {

  private static final Pattern AUTHZ_CALL = Pattern.compile("^@authz\\.\\w+\\(.*\\)$");

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
