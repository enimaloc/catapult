package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.admindata.AttributeClassifier;
import fr.enimaloc.catapult.admindata.AttributeKind;
import fr.enimaloc.catapult.admindata.DataRegistry;
import fr.enimaloc.catapult.admindata.IdCodec;
import fr.enimaloc.catapult.admindata.ValueCoercion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/data")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminDataController {

    private final DataRegistry registry;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public record RepoSummaryDto(String name, long size) {}

    @GetMapping
    public List<RepoSummaryDto> listRepositories() {
        return registry.entries().stream()
            .map(e -> new RepoSummaryDto(e.name(), e.repository().count()))
            .toList();
    }

    @GetMapping("/{repo}")
    @Transactional(readOnly = true)
    public Page<Map<String, Object>> entityList(
            @PathVariable String repo,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        DataRegistry.Entry entry = requireEntry(repo);
        List<Object> rows = queryRows(entry, q, page, size);
        long total = countRows(entry, q);
        List<Map<String, Object>> mapped = rows.stream().map(row -> toRowMap(row, entry.entityType())).toList();
        return new PageImpl<>(mapped, PageRequest.of(page, size), total);
    }

    private List<Object> queryRows(DataRegistry.Entry entry, String q, int page, int size) {
        StringBuilder jpql = new StringBuilder("SELECT e FROM ").append(entry.entityClass().getSimpleName()).append(" e");
        List<String> searchableFields = searchableStringFields(entry.entityType());
        boolean hasSearch = q != null && !q.isBlank() && !searchableFields.isEmpty();
        if (hasSearch) {
            jpql.append(" WHERE ");
            jpql.append(String.join(" OR ", searchableFields.stream()
                .map(f -> "LOWER(e." + f + ") LIKE :q").toList()));
        }
        TypedQuery<Object> query = entityManager.createQuery(jpql.toString(), Object.class);
        if (hasSearch) {
            query.setParameter("q", "%" + q.toLowerCase(Locale.ROOT) + "%");
        }
        return query.setFirstResult(page * size).setMaxResults(size).getResultList();
    }

    private long countRows(DataRegistry.Entry entry, String q) {
        List<String> searchableFields = searchableStringFields(entry.entityType());
        boolean hasSearch = q != null && !q.isBlank() && !searchableFields.isEmpty();
        if (!hasSearch) {
            return entry.repository().count();
        }
        StringBuilder jpql = new StringBuilder("SELECT COUNT(e) FROM ")
            .append(entry.entityClass().getSimpleName()).append(" e WHERE ")
            .append(String.join(" OR ", searchableFields.stream().map(f -> "LOWER(e." + f + ") LIKE :q").toList()));
        return entityManager.createQuery(jpql.toString(), Long.class)
            .setParameter("q", "%" + q.toLowerCase(Locale.ROOT) + "%")
            .getSingleResult();
    }

    private static List<String> searchableStringFields(EntityType<?> entityType) {
        return entityType.getAttributes().stream()
            .filter(a -> AttributeClassifier.classify(a) == AttributeKind.BASIC)
            .filter(a -> a instanceof SingularAttribute<?, ?> s && s.getJavaType() == String.class)
            .map(Attribute::getName)
            .toList();
    }

    @GetMapping("/{repo}/{id}")
    @Transactional(readOnly = true)
    public Map<String, Object> entityDetail(@PathVariable String repo, @PathVariable String id) {
        DataRegistry.Entry entry = requireEntry(repo);
        Object entity = findEntity(entry, id);
        Map<String, Object> row = toRowMap(entity, entry.entityType());
        for (Attribute<?, ?> attribute : entry.entityType().getAttributes()) {
            if (AttributeClassifier.classify(attribute) != AttributeKind.COLLECTION_RELATION) {
                continue;
            }
            Object rawCollection = readField(entity, attribute.getName());
            row.put(attribute.getName(), toCollectionLinks(rawCollection));
        }
        return row;
    }

    @PostMapping("/{repo}")
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable String repo, @RequestBody Map<String, String> body) {
        DataRegistry.Entry entry = requireEntry(repo);
        Object entity = instantiate(entry.entityClass());
        applyFields(entity, entry.entityType(), body);
        Object saved = entry.repository().save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(toRowMap(saved, entry.entityType()));
    }

    @PutMapping("/{repo}/{id}")
    public Map<String, Object> update(@PathVariable String repo, @PathVariable String id,
            @RequestBody Map<String, String> body) {
        DataRegistry.Entry entry = requireEntry(repo);
        Object entity = findEntity(entry, id);
        applyFields(entity, entry.entityType(), body);
        Object saved = entry.repository().save(entity);
        return toRowMap(saved, entry.entityType());
    }

    @DeleteMapping("/{repo}/{id}")
    public ResponseEntity<Void> delete(@PathVariable String repo, @PathVariable String id) {
        DataRegistry.Entry entry = requireEntry(repo);
        Object entityId = IdCodec.decode(id, entry.entityType());
        if (entry.repository().findById(entityId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown id " + id);
        }
        try {
            entry.repository().deleteById(entityId);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot delete: referenced by other data (" + e.getMostSpecificCause().getMessage() + ")");
        }
        return ResponseEntity.noContent().build();
    }

    @SuppressWarnings("unchecked")
    private Object findEntity(DataRegistry.Entry entry, String encodedId) {
        Object id = IdCodec.decode(encodedId, entry.entityType());
        java.util.Optional<Object> found = entry.repository().findById(id);
        return found.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown id " + encodedId));
    }

    private static Object instantiate(Class<?> entityClass) {
        try {
            var ctor = entityClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate " + entityClass, e);
        }
    }

    private void applyFields(Object entity, EntityType<?> entityType, Map<String, String> body) {
        for (Map.Entry<String, String> field : body.entrySet()) {
            Attribute<?, ?> attribute;
            try {
                attribute = entityType.getAttribute(field.getKey());
            } catch (IllegalArgumentException e) {
                continue; // unknown field name in the submitted form — ignore rather than fail
            }
            AttributeKind kind = AttributeClassifier.classify(attribute);
            try {
                Object value = switch (kind) {
                    case BASIC -> ValueCoercion.fromString(field.getValue(), attribute.getJavaType());
                    case CONVERTED -> objectMapper.readValue(field.getValue(), attribute.getJavaType());
                    case SINGULAR_RELATION -> resolveRelation(attribute, field.getValue());
                    case COLLECTION_RELATION -> null; // read-only, per spec — never written here
                };
                if (value != null || kind != AttributeKind.COLLECTION_RELATION) {
                    Field f = findField(entity.getClass(), attribute.getName());
                    f.setAccessible(true);
                    f.set(entity, value);
                }
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid value for field '" + field.getKey() + "': " + e.getMessage());
            }
        }
    }

    private Object resolveRelation(Attribute<?, ?> attribute, String encodedTargetId) {
        if (encodedTargetId == null || encodedTargetId.isBlank()) {
            return null;
        }
        EntityType<?> targetType = entityManager.getMetamodel().entity(attribute.getJavaType());
        Object targetId = IdCodec.decode(encodedTargetId, targetType);
        Object target = entityManager.find(attribute.getJavaType(), targetId);
        if (target == null) {
            throw new IllegalArgumentException(
                "No such " + attribute.getJavaType().getSimpleName() + " with id " + encodedTargetId);
        }
        return target;
    }

    private List<Map<String, String>> toCollectionLinks(Object rawCollection) {
        if (!(rawCollection instanceof java.util.Collection<?> collection)) {
            return List.of();
        }
        return collection.stream().map(item -> {
            Object real = org.hibernate.Hibernate.unproxy(item);
            EntityType<?> itemType = entityManager.getMetamodel().entity(real.getClass());
            Map<String, String> link = new LinkedHashMap<>();
            link.put("id", IdCodec.encode(readId(real, itemType), itemType));
            link.put("label", AttributeClassifier.label(real, itemType));
            return link;
        }).toList();
    }

    private Map<String, Object> toRowMap(Object entity, EntityType<?> entityType) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", IdCodec.encode(readId(entity, entityType), entityType));
        for (Attribute<?, ?> attribute : entityType.getAttributes()) {
            AttributeKind kind = AttributeClassifier.classify(attribute);
            if (kind == AttributeKind.COLLECTION_RELATION) {
                continue; // not shown in list rows — only in detail (Task 6)
            }
            Object value = readField(entity, attribute.getName());
            switch (kind) {
                case SINGULAR_RELATION -> {
                    if (value == null) {
                        row.put(attribute.getName(), null);
                    } else {
                        // `value` may be an uninitialized Hibernate proxy: its runtime getClass()
                        // is a dynamically generated proxy subclass (rejected by
                        // getMetamodel().entity(...)) and direct reflective field reads on the
                        // proxy itself (bypassing Hibernate's getters) would return default/unset
                        // values rather than the real data. Unproxy first to get the real,
                        // initialized target instance (safe here: entityList/entityDetail are
                        // @Transactional, so the session is still open).
                        Object real = org.hibernate.Hibernate.unproxy(value);
                        EntityType<?> targetType = entityManager.getMetamodel().entity(real.getClass());
                        // Emit the encoded target id as the field's round-trippable value (what a
                        // submitted edit form actually sends back to PUT/applyFields), and the
                        // human-readable label under a sibling "<field>_label" key purely for
                        // display — the label is not valid input for resolveRelation/IdCodec.decode.
                        row.put(attribute.getName(), IdCodec.encode(readId(real, targetType), targetType));
                        row.put(attribute.getName() + "_label", AttributeClassifier.label(real, targetType));
                    }
                }
                case CONVERTED -> row.put(attribute.getName(), toJson(value));
                default -> row.put(attribute.getName(), ValueCoercion.toString(value));
            }
        }
        return row;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize value " + value, e);
        }
    }

    private Object readId(Object entity, EntityType<?> entityType) {
        if (entityType.hasSingleIdAttribute()) {
            return readField(entity, entityType.getId(entityType.getIdType().getJavaType()).getName());
        }
        // Composite id: build the @IdClass instance from the entity's own @Id-annotated fields.
        try {
            Class<?> idClass = entityType.getIdType().getJavaType();
            Object pk = idClass.getDeclaredConstructor().newInstance();
            for (SingularAttribute<?, ?> idAttr : entityType.getIdClassAttributes()) {
                Object entityValue = readField(entity, idAttr.getName());
                Object idValue = idAttr.isAssociation() ? readId((Object) entityValue,
                    entityManager.getMetamodel().entity(idAttr.getJavaType())) : entityValue;
                Field f = idClass.getDeclaredField(idAttr.getName());
                f.setAccessible(true);
                f.set(pk, idValue);
            }
            return pk;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build composite id for " + entityType.getJavaType(), e);
        }
    }

    private static Object readField(Object entity, String fieldName) {
        try {
            Field f = findField(entity.getClass(), fieldName);
            f.setAccessible(true);
            return f.get(entity);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read field " + fieldName + " on " + entity.getClass(), e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " on " + type);
    }

    private DataRegistry.Entry requireEntry(String repo) {
        return registry.get(repo).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown repository " + repo));
    }
}
