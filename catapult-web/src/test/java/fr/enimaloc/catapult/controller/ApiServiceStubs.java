package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Test stubs shared by the controllers forwarding actions to a mocked {@link ApiService}. */
public final class ApiServiceStubs {

    private ApiServiceStubs() {
    }

    /**
     * Every action of {@code api} (the methods answering catapult-api's status) answers {@code status}, as
     * catapult-api would; a mock's own default, 0, would read as an unreachable catapult-api.
     */
    public static void actionsAnswer(ApiService api, int status) {
        for (Method method : ApiService.class.getMethods()) {
            if (method.getReturnType() != int.class || method.isDefault()) continue;
            Object stub = Mockito.doReturn(status).when(api);
            Class<?>[] types = method.getParameterTypes();
            Object[] matchers = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                matchers[i] = types[i] == boolean.class ? ArgumentMatchers.anyBoolean()
                        : types[i] == int.class ? ArgumentMatchers.anyInt()
                        : types[i] == long.class ? ArgumentMatchers.anyLong()
                        : ArgumentMatchers.any();
            }
            try {
                method.invoke(stub, matchers);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Cannot stub " + method, e);
            }
        }
    }
}
