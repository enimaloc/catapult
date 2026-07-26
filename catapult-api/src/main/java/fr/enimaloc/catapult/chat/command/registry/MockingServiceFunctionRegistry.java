package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.domain.UserAccount;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Wraps a real {@link ServiceFunctionRegistry} so the command editor's "Tester" button can fake
 * the return value of specific service calls (steam#getGame, catapult#getCurrentGame, ...)
 * instead of always hitting the real gateway/DB during a test run — mirrors how {@code
 * ctx.placeholder} overrides already let a streamer test branches that depend on a legacy
 * placeholder without actually being live.
 *
 * <p>One mock value per {@code namespace#function}, reused regardless of the arguments a
 * particular call site passes — matches the single "test value" field per function the editor
 * exposes, rather than a separate mock per distinct argument combination.
 */
public class MockingServiceFunctionRegistry extends ServiceFunctionRegistry {

    private final ServiceFunctionRegistry delegate;
    private final Map<String, Object> mocks;

    public MockingServiceFunctionRegistry(ServiceFunctionRegistry delegate, Map<String, Object> mocks) {
        this.delegate = delegate;
        this.mocks = mocks;
    }

    @Override
    public Optional<ServiceFunction> lookup(String namespace, String name) {
        Object mock = mocks.get(namespace + "#" + name);
        Optional<ServiceFunction> real = delegate.lookup(namespace, name);
        if (mock == null || real.isEmpty()) {
            return real;
        }
        return Optional.of(new MockedServiceFunction(real.get(), mock));
    }

    private record MockedServiceFunction(ServiceFunction real, Object mockValue) implements ServiceFunction {
        @Override
        public String namespace() {
            return real.namespace();
        }

        @Override
        public String name() {
            return real.name();
        }

        @Override
        public List<String> parameterNames() {
            return real.parameterNames();
        }

        @Override
        public List<String> returnKeys() {
            return real.returnKeys();
        }

        @Override
        public List<String> optionalParameterNames() {
            return real.optionalParameterNames();
        }

        @Override
        public boolean isAction() {
            return real.isAction();
        }

        @Override
        public Object invoke(UserAccount user, Object[] args) {
            return mockValue;
        }
    }
}
