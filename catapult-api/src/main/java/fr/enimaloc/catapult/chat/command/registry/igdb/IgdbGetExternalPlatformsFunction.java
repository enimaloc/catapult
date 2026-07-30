package fr.enimaloc.catapult.chat.command.registry.igdb;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * External store/website links for a game (Steam, Xbox, official site, ...), keyed by
 * lowercased source name. A separate call from {@code igdb#getGame} so a command that doesn't
 * need this data doesn't pay for fetching or rendering it.
 */
@Component
@RequiredArgsConstructor
public class IgdbGetExternalPlatformsFunction implements ServiceFunction {

    private final ChatCommandServiceGateway gateway;

    @Override
    public String namespace() {
        return "igdb";
    }

    @Override
    public String name() {
        return "getExternalPlatforms";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("game");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        String igdbId = IgdbIdArg.resolve(args[0]);
        return gateway.igdbExternalPlatforms(igdbId).orElse(Map.of());
    }
}
