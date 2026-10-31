package fr.enimaloc.catapult.chat.command.registry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ServiceFunctionRegistry {

    private final Map<String, ServiceFunction> functions = new LinkedHashMap<>();

    /** For tests that build a registry and register functions manually. */
    public ServiceFunctionRegistry() {
    }

    /**
     * Spring collects every {@link ServiceFunction} bean (IgdbGetGameFunction,
     * TwitchGetUserFunction, SteamGetPriceFunction, ...) into this list automatically —
     * this is what actually wires the "register new ones as Spring beans" contract
     * documented on {@link ServiceFunction} into a live registry at boot. Without this,
     * the registry stayed empty at runtime and every service call failed with
     * "Unknown service function".
     */
    @Autowired
    public ServiceFunctionRegistry(List<ServiceFunction> functions) {
        functions.forEach(this::register);
    }

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
