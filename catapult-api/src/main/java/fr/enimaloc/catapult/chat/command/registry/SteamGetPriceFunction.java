package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
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
    public Object invoke(Object[] args) {
        return gateway.steamPrice(String.valueOf(args[0])).orElse(null);
    }
}
