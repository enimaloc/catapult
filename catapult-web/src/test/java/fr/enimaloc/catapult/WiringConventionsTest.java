package fr.enimaloc.catapult;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * Wiring conventions: every dependency is a plain constructor parameter. No {@code @Lazy}
 * injection, no {@code @Autowired(required = false)}, no setter injection on a Spring component.
 */
class WiringConventionsTest {

  @Test
  void noLazyOrOptionalInjection() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

    List<String> violations = new ArrayList<>();
    for (BeanDefinition definition : scanner.findCandidateComponents("fr.enimaloc.catapult")) {
      Class<?> type = Class.forName(definition.getBeanClassName());
      for (var field : type.getDeclaredFields()) {
        String name = type.getName() + "#" + field.getName();
        if (isLazy(field)) {
          violations.add(name + " @Lazy");
        }
        if (isOptionalAutowired(field)) {
          violations.add(name + " @Autowired(required = false)");
        }
      }
      for (var constructor : type.getDeclaredConstructors()) {
        collectParameters(type, constructor, violations);
        if (isLazy(constructor)) {
          violations.add(type.getName() + "#<init> @Lazy");
        }
      }
      for (Method method : type.getDeclaredMethods()) {
        String name = type.getName() + "#" + method.getName();
        collectParameters(type, method, violations);
        if (isLazy(method)) {
          violations.add(name + " @Lazy");
        }
        if (isOptionalAutowired(method)) {
          violations.add(name + " @Autowired(required = false)");
        }
        if (MergedAnnotations.from(method).isPresent(Autowired.class)) {
          violations.add(name + " setter injection (@Autowired method)");
        }
      }
    }

    assertThat(violations).isEmpty();
  }

  private static void collectParameters(Class<?> type, Executable executable, List<String> out) {
    for (Parameter parameter : executable.getParameters()) {
      if (isLazy(parameter)) {
        out.add(
            type.getName()
                + "#"
                + (executable instanceof Method ? executable.getName() : "<init>")
                + "("
                + parameter.getName()
                + ") @Lazy");
      }
    }
  }

  private static boolean isLazy(AnnotatedElement element) {
    return MergedAnnotations.from(element).isPresent(Lazy.class);
  }

  private static boolean isOptionalAutowired(AnnotatedElement element) {
    var autowired = MergedAnnotations.from(element).get(Autowired.class);
    return autowired.isPresent() && !autowired.getBoolean("required");
  }
}
