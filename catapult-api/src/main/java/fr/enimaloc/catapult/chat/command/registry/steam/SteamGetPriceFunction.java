package fr.enimaloc.catapult.chat.command.registry.steam;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import fr.enimaloc.catapult.domain.UserAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SteamGetPriceFunction implements ServiceFunction {

    private final ChatCommandServiceGateway gateway;

    @Override
    public String namespace() {
        return "steam";
    }

    @Override
    public String name() {
        return "getPrice";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("appId");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        return gateway.steamPrice(String.valueOf(args[0])).orElse(null);
    }
}
