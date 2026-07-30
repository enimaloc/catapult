package fr.enimaloc.catapult.chat.command.registry.igdb;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Comma-joined game mode names (e.g. Single player, Co-op). A separate call from {@code igdb#getGame}, see {@link IgdbIdArg}. */
@Component
@RequiredArgsConstructor
public class IgdbGetGameModesFunction implements ServiceFunction {

    private final ChatCommandServiceGateway gateway;

    @Override
    public String namespace() {
        return "igdb";
    }

    @Override
    public String name() {
        return "getGameModes";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("game");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        return gateway.igdbGameModes(IgdbIdArg.resolve(args[0])).orElse(java.util.List.of());
    }
}
