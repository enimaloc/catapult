package fr.enimaloc.catapult.chat.command.registry.igdb;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import fr.enimaloc.catapult.domain.UserAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class IgdbGetGameFunction implements ServiceFunction {

    private final ChatCommandServiceGateway gateway;

    @Override
    public String namespace() {
        return "igdb";
    }

    @Override
    public String name() {
        return "getGame";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("query");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        return gateway.igdbGameName(String.valueOf(args[0])).orElse(null);
    }
}
