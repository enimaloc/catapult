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

    /**
     * A copy holding only the functions that read: none of the {@link ServiceFunction#isAction()}
     * ones, whose whole point is to change something (a ban, a message, the game, a setting). A
     * command run with it calling one fails as on an unknown function.
     */
    public ServiceFunctionRegistry readOnly() {
        ServiceFunctionRegistry copy = new ServiceFunctionRegistry();
        functions.values().stream().filter(fn -> !fn.isAction()).forEach(copy::register);
        return copy;
    }

    /**
     * Whether a command's source (its DSL template, or its ejected JS) names one of the {@link
     * ServiceFunction#isAction()} functions, in any of the forms it can call one: {@code ns#fn} in
     * the DSL, {@code ns.fn} or {@code ctx.call("ns", "fn")} in JS. A name in a string or a comment
     * counts too: better to say a command may act than to miss one that does.
     */
    public boolean namesAnAction(String source) {
        if (source == null || source.isBlank()) return false;
        return functions.values().stream().filter(ServiceFunction::isAction).anyMatch(fn ->
            source.contains(fn.namespace() + "#" + fn.name())
                || source.contains(fn.namespace() + "." + fn.name())
                || source.contains("\"" + fn.name() + "\""));
    }

    private static String key(String namespace, String name) {
        return namespace + "#" + name;
    }
}
