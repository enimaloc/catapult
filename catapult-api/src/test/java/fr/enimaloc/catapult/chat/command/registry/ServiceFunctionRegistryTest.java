package fr.enimaloc.catapult.chat.command.registry;

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
            @Override public Object invoke(Object[] args) { return args[0]; }
        };
        registry.register(fn);

        Optional<ServiceFunction> found = registry.lookup("test", "echo");
        assertThat(found).isPresent();
        assertThat(found.get().parameterNames()).containsExactly("value");
        assertThat(registry.lookup("test", "missing")).isEmpty();
        assertThat(registry.all()).hasSize(1);
    }
}
