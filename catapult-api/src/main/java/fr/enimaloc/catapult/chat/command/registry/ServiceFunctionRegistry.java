package fr.enimaloc.catapult.chat.command.registry;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class ServiceFunctionRegistry {

    private final Map<String, ServiceFunction> functions = new LinkedHashMap<>();

    public void register(ServiceFunction fn) {
        functions.put(key(fn.namespace(), fn.name()), fn);
    }

    public Optional<ServiceFunction> lookup(String namespace, String name) {
        return Optional.ofNullable(functions.get(key(namespace, name)));
    }

    public Collection<ServiceFunction> all() {
        return functions.values();
    }

    private static String key(String namespace, String name) {
        return namespace + "#" + name;
    }
}
