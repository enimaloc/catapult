package fr.enimaloc.catapult.chat.command.js;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxExecutorServiceCallTest {

    private final SandboxExecutor executor = new SandboxExecutor();

    @Test
    void serviceCallDelegatesToRegisteredFunction() {
        ServiceFunctionRegistry registry = new ServiceFunctionRegistry();
        registry.register(new ServiceFunction() {
            @Override public String namespace() { return "igdb"; }
            @Override public String name() { return "getGame"; }
            @Override public List<String> parameterNames() { return List.of("query"); }
            @Override public Object invoke(UserAccount user, Object[] args) { return "resolved:" + args[0]; }
        });

        String js = "let result = \"\"; result += ctx.call(\"igdb\", \"getGame\", \"Valorant\"); return result;";
        String output = executor.execute(js, path -> null, name -> List.of(), registry, null, Duration.ofSeconds(2));

        assertThat(output).isEqualTo("resolved:Valorant");
    }

    @Test
    void serviceCallReceivesTheBoundUser() {
        UserAccount user = new UserAccount();
        ServiceFunctionRegistry registry = new ServiceFunctionRegistry();
        registry.register(new ServiceFunction() {
            @Override public String namespace() { return "test"; }
            @Override public String name() { return "whoAmI"; }
            @Override public List<String> parameterNames() { return List.of(); }
            @Override public Object invoke(UserAccount boundUser, Object[] args) {
                return boundUser == user ? "same-user" : "different-user";
            }
        });

        String js = "return ctx.call(\"test\", \"whoAmI\");";
        String output = executor.execute(js, path -> null, name -> List.of(), registry, user, Duration.ofSeconds(2));

        assertThat(output).isEqualTo("same-user");
    }

    @Test
    void serviceCallOnUnknownFunctionFailsClearly() {
        ServiceFunctionRegistry registry = new ServiceFunctionRegistry();

        String js = "return ctx.call(\"igdb\", \"getGame\", \"Valorant\");";
        assertThatThrownBy(() -> executor.execute(js, path -> null, name -> List.of(), registry, null, Duration.ofSeconds(2)))
            .isInstanceOf(SandboxExecutionException.class)
            .satisfies(t -> assertThat(fullMessageChain(t)).contains("igdb#getGame"));
    }

    private String fullMessageChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        while (t != null) {
            sb.append(t.getMessage()).append(" | ");
            t = t.getCause();
        }
        return sb.toString();
    }

    @Test
    void serviceCallWithoutRegistryFailsClearly() {
        String js = "return ctx.call(\"igdb\", \"getGame\", \"Valorant\");";
        assertThatThrownBy(() -> executor.execute(js, path -> null, name -> List.of(), Duration.ofSeconds(2)))
            .isInstanceOf(SandboxExecutionException.class);
    }
}
