package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import fr.enimaloc.catapult.domain.UserAccount;
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
    public Object invoke(UserAccount user, Object[] args) {
        return gateway.twitchOwnDisplayName(user).orElse(null);
    }
}
