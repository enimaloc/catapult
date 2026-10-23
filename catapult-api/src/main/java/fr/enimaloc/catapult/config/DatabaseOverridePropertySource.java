package fr.enimaloc.catapult.config;

import org.springframework.core.env.EnumerablePropertySource;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DatabaseOverridePropertySource extends EnumerablePropertySource<Map<String, Object>> {
    public static final String NAME = "databaseOverrides";

    // Keys whose override is ignored: a profile (degraded) sets them and must win over the admin's value.
    private final Set<String> pinned = ConcurrentHashMap.newKeySet();

    public DatabaseOverridePropertySource() {
        super(NAME, new ConcurrentHashMap<>());
    }

    @Override
    public String[] getPropertyNames() {
        return source.keySet().stream().filter(k -> !pinned.contains(k)).toArray(String[]::new);
    }

    @Override
    public Object getProperty(String name) {
        return pinned.contains(name) ? null : source.get(name);
    }

    /** The stored override for the key, whether a pinned profile value hides it or not. */
    public Optional<String> override(String key) {
        return Optional.ofNullable(source.get(key)).map(Object::toString);
    }

    public void put(String key, String value) {
        source.put(key, value);
    }

    public void remove(String key) {
        source.remove(key);
    }

    public void pin(Set<String> keys) {
        pinned.addAll(keys);
    }

    /** True when an override is stored for the key but a pinned profile value hides it. */
    public boolean ignores(String key) {
        return pinned.contains(key) && source.containsKey(key);
    }

    public Set<String> keys() {
        return source.keySet();
    }
}
