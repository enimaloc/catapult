package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceFunctionRegistryTest {

    @Test
    void registeredFunctionIsFoundByNamespaceAndName() {
        ServiceFunctionRegistry registry = new ServiceFunctionRegistry();
        ServiceFunction fn = new ServiceFunction() {
            @Override public String namespace() { return "test"; }
            @Override public String name() { return "echo"; }
            @Override public List<String> parameterNames() { return List.of("value"); }
            @Override public Object invoke(UserAccount user, Object[] args) { return args[0]; }
        };
        registry.register(fn);

        Optional<ServiceFunction> found = registry.lookup("test", "echo");
        assertThat(found).isPresent();
        assertThat(found.get().parameterNames()).containsExactly("value");
        assertThat(registry.lookup("test", "missing")).isEmpty();
        assertThat(registry.all()).hasSize(1);
    }

    @Test
    void constructorRegistersEveryInjectedFunction() {
        // This is what Spring actually calls at boot, autowiring every ServiceFunction
        // bean (IgdbGetGameFunction, TwitchGetUserFunction, SteamGetPriceFunction) into
        // this constructor's List<ServiceFunction> — without it the registry silently
        // stayed empty and every service call failed with "Unknown service function".
        ServiceFunction a = new ServiceFunction() {
            @Override public String namespace() { return "ns1"; }
            @Override public String name() { return "fnA"; }
            @Override public List<String> parameterNames() { return List.of(); }
            @Override public Object invoke(UserAccount user, Object[] args) { return null; }
        };
        ServiceFunction b = new ServiceFunction() {
            @Override public String namespace() { return "ns2"; }
            @Override public String name() { return "fnB"; }
            @Override public List<String> parameterNames() { return List.of("x"); }
            @Override public Object invoke(UserAccount user, Object[] args) { return null; }
        };

        ServiceFunctionRegistry registry = new ServiceFunctionRegistry(List.of(a, b));

        assertThat(registry.all()).hasSize(2);
        assertThat(registry.lookup("ns1", "fnA")).contains(a);
        assertThat(registry.lookup("ns2", "fnB")).contains(b);
    }
}
