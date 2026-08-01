package fr.enimaloc.catapult.admindata;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Discovers every {@link JpaRepository} bean in the application context and resolves the JPA
 * entity type it manages, so the admin data viewer needs zero per-repository registration.
 */
@Slf4j
@Component
public class DataRegistry {

    @SuppressWarnings("rawtypes")
    public record Entry(String name, JpaRepository repository, EntityType<?> entityType, Class<?> entityClass) {}

    private final ApplicationContext applicationContext;
    private final EntityManager entityManager;
    private Map<String, Entry> entries = Map.of();

    public DataRegistry(ApplicationContext applicationContext, EntityManager entityManager) {
        this.applicationContext = applicationContext;
        this.entityManager = entityManager;
    }

    @PostConstruct
    void init() {
        Map<String, Entry> built = new TreeMap<>();
        Map<String, JpaRepository> beans = applicationContext.getBeansOfType(JpaRepository.class);
        for (Map.Entry<String, JpaRepository> bean : beans.entrySet()) {
            JpaRepository<?, ?> repository = bean.getValue();
            Class<?> entityClass = resolveEntityClass(repository);
            if (entityClass == null) {
                log.warn("Skipping repository bean '{}' ({}): could not resolve its managed entity type",
                        bean.getKey(), repository.getClass());
                continue;
            }
            EntityType<?> entityType = entityManager.getMetamodel().entity(entityClass);
            String name = toKebabCase(entityClass.getSimpleName());
            built.put(name, new Entry(name, repository, entityType, entityClass));
        }
        this.entries = new LinkedHashMap<>(built);
    }

    public Collection<Entry> entries() {
        return entries.values().stream().sorted(Comparator.comparing(Entry::name)).toList();
    }

    public Optional<Entry> get(String name) {
        return Optional.ofNullable(entries.get(name));
    }

    private static Class<?> resolveEntityClass(JpaRepository<?, ?> repository) {
        for (Class<?> iface : allInterfaces(repository.getClass())) {
            for (Type generic : iface.getGenericInterfaces()) {
                if (generic instanceof ParameterizedType pt
                        && JpaRepository.class.isAssignableFrom((Class<?>) pt.getRawType())) {
                    Type entityArg = pt.getActualTypeArguments()[0];
                    if (entityArg instanceof Class<?> entityClass) {
                        return entityClass;
                    }
                }
            }
        }
        return null;
    }

    private static Iterable<Class<?>> allInterfaces(Class<?> proxyClass) {
        return java.util.Arrays.asList(proxyClass.getInterfaces());
    }

    private static String toKebabCase(String entityClassName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entityClassName.length(); i++) {
            char c = entityClassName.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('-');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
