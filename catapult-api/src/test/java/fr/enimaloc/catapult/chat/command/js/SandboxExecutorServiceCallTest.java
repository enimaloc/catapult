package fr.enimaloc.catapult.chat.command.js;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
        String output = executor.execute(js, path -> null, name -> List.of(), registry, null, null, Duration.ofSeconds(2));

        assertThat(output).isEqualTo("resolved:Valorant");
    }

    @Test
    void namespaceObjectMethodDelegatesToRegisteredFunctionLikeCtxCallDoes() {
        ServiceFunctionRegistry registry = new ServiceFunctionRegistry();
        registry.register(new ServiceFunction() {
            @Override public String namespace() { return "igdb"; }
            @Override public String name() { return "getGame"; }
            @Override public List<String> parameterNames() { return List.of("query"); }
            @Override public Object invoke(UserAccount user, Object[] args) { return "resolved:" + args[0]; }
        });

        String js = "return igdb.getGame(\"Valorant\");";
        String output = executor.execute(js, path -> null, name -> List.of(), registry, null, null, Duration.ofSeconds(2));

        assertThat(output).isEqualTo("resolved:Valorant");
    }

    @Test
    void distinctNamespacesGetDistinctObjectsWithOnlyTheirOwnFunctions() {
        ServiceFunctionRegistry registry = new ServiceFunctionRegistry();
        registry.register(new ServiceFunction() {
            @Override public String namespace() { return "igdb"; }
            @Override public String name() { return "getGame"; }
            @Override public List<String> parameterNames() { return List.of(); }
            @Override public Object invoke(UserAccount user, Object[] args) { return "igdb-result"; }
        });
        registry.register(new ServiceFunction() {
            @Override public String namespace() { return "steam"; }
            @Override public String name() { return "getGame"; }
            @Override public List<String> parameterNames() { return List.of(); }
            @Override public Object invoke(UserAccount user, Object[] args) { return "steam-result"; }
        });

        String js = "return igdb.getGame() + \"/\" + steam.getGame();";
        String output = executor.execute(js, path -> null, name -> List.of(), registry, null, null, Duration.ofSeconds(2));

        assertThat(output).isEqualTo("igdb-result/steam-result");
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
        String output = executor.execute(js, path -> null, name -> List.of(), registry, user, null, Duration.ofSeconds(2));

        assertThat(output).isEqualTo("same-user");
    }

    @Test
    void diagnosticMapPropertyAccessOnServiceCallResult() {
        ServiceFunctionRegistry registry = new ServiceFunctionRegistry();
        registry.register(new ServiceFunction() {
            @Override public String namespace() { return "test"; }
            @Override public String name() { return "getMap"; }
            @Override public List<String> parameterNames() { return List.of(); }
            @Override public Object invoke(UserAccount user, Object[] args) {
                return java.util.Map.of("title", "Hello");
            }
        });

        String js = "let s = ctx.call(\"test\", \"getMap\"); return s[\"title\"];";
        String output = executor.execute(js, path -> null, name -> List.of(), registry, null, null, Duration.ofSeconds(2));

        assertThat(output).isEqualTo("Hello");
    }

    @Test
    void serviceCallOnUnknownFunctionFailsClearly() {
        ServiceFunctionRegistry registry = new ServiceFunctionRegistry();

        String js = "return ctx.call(\"igdb\", \"getGame\", \"Valorant\");";
        assertThatThrownBy(() -> executor.execute(js, path -> null, name -> List.of(), registry, null, null, Duration.ofSeconds(2)))
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

    // Regression coverage for the bug fixed on IgdbGetGameFunction: a bare empty Map (Map.of())
    // makes every field read back as JS `undefined` through the sandbox's Map interop, and
    // `undefined == ""` is false — exactly like a real populated value would also be false — so
    // an `== ""` not-found check is indistinguishable from a found result. A function that
    // instead returns an object with every field present but blank (as IgdbGetGameFunction now
    // does) makes that check actually work; this proves it end-to-end through the real sandbox,
    // not just at the function-unit level, since the bug only manifests through GraalJS's Map
    // interop, not in plain Java.
    @Test
    void objectFieldEqualsEmptyStringDistinguishesFoundFromNotFoundOnlyWhenEveryFieldIsPresent() {
        ServiceFunctionRegistry registry = new ServiceFunctionRegistry();
        registry.register(new ServiceFunction() {
            @Override public String namespace() { return "igdb"; }
            @Override public String name() { return "getGame"; }
            @Override public List<String> parameterNames() { return List.of("query"); }
            @Override public Object invoke(UserAccount user, Object[] args) {
                return "found".equals(args[0]) ? Map.of("name", "VALORANT") : Map.of("name", "");
            }
        });
        // ctx.list("args")[0], not arg(0) — this test feeds pre-compiled JS straight to the
        // sandbox, and arg(...) is text-DSL sugar JsCompiler expands, not real JS.
        String js = "let r = igdb.getGame(ctx.list(\"args\")[0]); "
            + "return (r.name == \"\") ? \"NOT_FOUND\" : \"FOUND:\" + r.name;";

        String foundOutput = executor.execute(js, path -> null, name -> List.of("found"), registry, null, null, Duration.ofSeconds(2));
        String notFoundOutput = executor.execute(js, path -> null, name -> List.of("missing"), registry, null, null, Duration.ofSeconds(2));

        assertThat(foundOutput).isEqualTo("FOUND:VALORANT");
        assertThat(notFoundOutput).isEqualTo("NOT_FOUND");
    }
}
