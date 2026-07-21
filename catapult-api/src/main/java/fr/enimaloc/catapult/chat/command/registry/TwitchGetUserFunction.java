package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TwitchGetUserFunction implements ServiceFunction {

    private final ChatCommandServiceGateway gateway;

    @Override
    public String namespace() {
        return "twitch";
    }

    @Override
    public String name() {
        return "getUser";
    }

    @Override
    public List<String> parameterNames() {
        return List.of();
    }

    @Override
    public Object invoke(Object[] args) {
        // The invoking UserAccount is bound per-execution by SandboxExecutor
        // (see Task 6) via a ThreadLocal-free context parameter, not stored here.
        throw new UnsupportedOperationException("bound per-call by SandboxExecutor#bindContextFunctions");
    }
}
