package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MockingServiceFunctionRegistryTest {

    private static class StubFunction implements ServiceFunction {
        @Override public String namespace() { return "steam"; }
        @Override public String name() { return "getGame"; }
        @Override public List<String> parameterNames() { return List.of("appId"); }
        @Override public List<String> returnKeys() { return List.of("name"); }
        @Override public Object invoke(UserAccount user, Object[] args) { return Map.of("name", "Real value"); }
    }

    @Test
    void lookupReturnsTheMockedValueWhenAMockIsConfiguredForThatFunction() throws Exception {
        ServiceFunctionRegistry delegate = new ServiceFunctionRegistry();
        delegate.register(new StubFunction());

        MockingServiceFunctionRegistry mocking = new MockingServiceFunctionRegistry(
            delegate, Map.of("steam#getGame", Map.of("name", "Mocked value")));

        ServiceFunction fn = mocking.lookup("steam", "getGame").orElseThrow();
        assertThat(fn.namespace()).isEqualTo("steam");
        assertThat(fn.name()).isEqualTo("getGame");
        assertThat(fn.parameterNames()).containsExactly("appId");
        assertThat(fn.returnKeys()).containsExactly("name");
        assertThat(fn.invoke(null, new Object[]{"anything"})).isEqualTo(Map.of("name", "Mocked value"));
    }

    @Test
    void lookupFallsThroughToTheRealFunctionWhenNoMockIsConfigured() throws Exception {
        ServiceFunctionRegistry delegate = new ServiceFunctionRegistry();
        delegate.register(new StubFunction());

        MockingServiceFunctionRegistry mocking = new MockingServiceFunctionRegistry(delegate, Map.of());

        ServiceFunction fn = mocking.lookup("steam", "getGame").orElseThrow();
        assertThat(fn.invoke(null, new Object[]{"730"})).isEqualTo(Map.of("name", "Real value"));
    }

    @Test
    void lookupReturnsEmptyForAnUnknownFunctionEvenWithMocksConfigured() {
        ServiceFunctionRegistry delegate = new ServiceFunctionRegistry();
        MockingServiceFunctionRegistry mocking = new MockingServiceFunctionRegistry(
            delegate, Map.of("steam#getGame", "irrelevant"));

        assertThat(mocking.lookup("steam", "getGame")).isEmpty();
    }
}
