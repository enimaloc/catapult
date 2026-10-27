package fr.enimaloc.catapult.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.debug.DebugRecording;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * Controller conventions: a controller resolves the caller, validates the request and shapes the response; it holds no
 * repository, entity manager, outbound client, event publisher or debug recorder, opens no transaction and does not
 * scope debug attribution.
 */
class ControllerConventionsTest {

  private static final Path SOURCES = Path.of("src/main/java");

  @Test
  void controllersFollowTheConventions() throws Exception {
    Set<String> violations = new TreeSet<>();
    List<Class<?>> controllers = controllers();
    assertThat(controllers).as("the scan finds the controllers").hasSizeGreaterThanOrEqualTo(60);
    for (Class<?> controller : controllers) {
      violations.addAll(violations(controller, SOURCES));
    }
    assertThat(violations).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      classes = {
        WithRepository.class,
        WithOptionalClient.class,
        WithTransaction.class,
        WithEventPublisher.class
      })
  void theRulesCatchWhatTheyName(Class<?> fixture) {
    assertThat(violations(fixture, SOURCES)).hasSize(1);
  }

  @Test
  void classLevelTransactionIsCaught() {
    assertThat(violations(WithClassTransaction.class, SOURCES))
        .containsExactly("WithClassTransaction is @Transactional");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "DebugContext.runAs(id, () -> x());",
        "import static fr.enimaloc.catapult.service.debug.DebugContext.callAs;"
      })
  void debugContextUseIsCaught(String source) {
    assertThat(callsDebugContext(source)).isTrue();
  }

  @Test
  void importingDebugContextAloneIsNotAUse() {
    assertThat(callsDebugContext("import fr.enimaloc.catapult.service.debug.DebugContext;"))
        .isFalse();
  }

  @Test
  void aMissingControllerSourceFailsLoudly() {
    assertThatThrownBy(() -> violations(ApiUserResolver.class, Path.of("nowhere")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nowhere");
  }

  @Test
  void aThinControllerPasses() {
    assertThat(violations(Thin.class, SOURCES)).isEmpty();
  }

  /** The violations of one controller, formatted as "Controller depends on Type" style entries. */
  static Set<String> violations(Class<?> controller, Path sources) {
    String name = controller.getSimpleName();
    Set<String> found = new TreeSet<>();
    for (Type type : injectedTypes(controller)) {
      Class<?> raw = rawClass(unwrap(type));
      if (raw != null && isForbidden(raw)) {
        found.add(name + " depends on " + raw.getSimpleName());
      }
    }
    if (MergedAnnotations.from(controller).isPresent(Transactional.class)
        || MergedAnnotations.from(controller).isPresent(jakarta.transaction.Transactional.class)) {
      found.add(name + " is @Transactional");
    }
    for (Method method : controller.getDeclaredMethods()) {
      if (MergedAnnotations.from(method).isPresent(Transactional.class)
          || MergedAnnotations.from(method).isPresent(jakarta.transaction.Transactional.class)) {
        found.add(name + "#" + method.getName() + " is @Transactional");
      }
    }
    if (callsDebugContext(source(controller, sources))) {
      found.add(name + " calls DebugContext");
    }
    return found;
  }

  private static List<Type> injectedTypes(Class<?> controller) {
    List<Type> types = new ArrayList<>();
    for (var field : controller.getDeclaredFields()) {
      types.add(field.getGenericType());
    }
    for (Constructor<?> constructor : controller.getDeclaredConstructors()) {
      for (var parameter : constructor.getParameters()) {
        types.add(parameter.getParameterizedType());
      }
    }
    return types;
  }

  /** {@code Optional<T>} and {@code List<T>} stand for their element. */
  private static Type unwrap(Type type) {
    if (type instanceof ParameterizedType parameterized
        && (parameterized.getRawType() == Optional.class || parameterized.getRawType() == List.class)) {
      return unwrap(parameterized.getActualTypeArguments()[0]);
    }
    return type;
  }

  private static Class<?> rawClass(Type type) {
    if (type instanceof Class<?> clazz) {
      return clazz;
    }
    if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> clazz) {
      return clazz;
    }
    return null;
  }

  private static boolean isForbidden(Class<?> type) {
    return Repository.class.isAssignableFrom(type)
        || type == EntityManager.class
        || type == RestClient.class
        || type.getName().equals("org.springframework.web.reactive.function.client.WebClient")
        || type == RestTemplate.class
        || type == ApplicationEventPublisher.class
        || type == DebugRecording.class
        || (type.getName().startsWith("fr.enimaloc.catapult.") && type.getSimpleName().endsWith("Client"));
  }

  static boolean callsDebugContext(String source) {
    return source.contains("DebugContext.");
  }

  private static String source(Class<?> controller, Path sources) {
    Class<?> top = controller;
    while (top.getEnclosingClass() != null) {
      top = top.getEnclosingClass();
    }
    Path file = sources.resolve(top.getName().replace('.', '/') + ".java");
    if (!Files.exists(file)) {
      if (top == ControllerConventionsTest.class) {
        return "";
      }
      throw new IllegalStateException("controller source not found: " + file.toAbsolutePath());
    }
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new IllegalStateException(file.toString(), e);
    }
  }

  private static List<Class<?>> controllers() throws ClassNotFoundException {
    // @Conditional* is not evaluated: a controller behind a disabled property is still a controller.
    AnnotationTypeFilter filter = new AnnotationTypeFilter(Controller.class);
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false) {
          @Override
          protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
            return filter.match(reader, getMetadataReaderFactory());
          }
        };
    List<Class<?>> controllers = new ArrayList<>();
    for (BeanDefinition definition : scanner.findCandidateComponents("fr.enimaloc.catapult")) {
      if (definition.getBeanClassName().contains("ControllerConventionsTest$")) {
        continue;
      }
      Class<?> type = Class.forName(definition.getBeanClassName());
      // Fixtures declared by other tests live in the test classes, not in src/main.
      if (!type.getProtectionDomain().getCodeSource().getLocation().getPath().contains("/test/")) {
        controllers.add(type);
      }
    }
    return controllers;
  }

  @RestController
  static class WithRepository {
    private final UserAccountRepository r;

    WithRepository(UserAccountRepository r) {
      this.r = r;
    }
  }

  @RestController
  static class WithOptionalClient {
    private final Optional<DtddApiClient> client;

    WithOptionalClient(Optional<DtddApiClient> client) {
      this.client = client;
    }
  }

  @RestController
  static class WithTransaction {
    @Transactional
    void write() {}
  }

  @RestController
  @Transactional
  static class WithClassTransaction {}

  @RestController
  static class WithEventPublisher {
    private final ApplicationEventPublisher events;

    WithEventPublisher(ApplicationEventPublisher events) {
      this.events = events;
    }
  }

  @RestController
  static class Thin {
    private final ApiUserResolver resolver;
    private final Object service;

    Thin(ApiUserResolver resolver, Object service) {
      this.resolver = resolver;
      this.service = service;
    }
  }
}
