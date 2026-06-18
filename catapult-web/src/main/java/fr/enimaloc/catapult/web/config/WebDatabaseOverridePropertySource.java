package fr.enimaloc.catapult.web.config;

import org.springframework.core.env.EnumerablePropertySource;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WebDatabaseOverridePropertySource extends EnumerablePropertySource<Map<String, Object>> {
    public static final String NAME = "webDatabaseOverrides";

    public WebDatabaseOverridePropertySource() {
        super(NAME, new ConcurrentHashMap<>());
    }

    @Override
    public String[] getPropertyNames() {
        return source.keySet().toArray(new String[0]);
    }

    @Override
    public Object getProperty(String name) {
        return source.get(name);
    }

    public void put(String key, String value) {
        source.put(key, value);
    }

    public void remove(String key) {
        source.remove(key);
    }

    public Set<String> keys() {
        return source.keySet();
    }
}
