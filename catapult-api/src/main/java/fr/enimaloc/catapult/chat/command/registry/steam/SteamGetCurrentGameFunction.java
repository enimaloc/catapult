package fr.enimaloc.catapult.chat.command.registry.steam;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.GameStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Convenience wrapper around {@code steam#getGame(appId)} that resolves {@code appId} itself
 * from the streamer's currently detected game, instead of the caller having to check {@code
 * catapult#getGame().sourceType == "STEAM"} and thread {@code .sourceId} through by hand.
 */
@Component
@RequiredArgsConstructor
public class SteamGetCurrentGameFunction implements ServiceFunction {

    private final GameStateService gameStateService;
    private final SteamGetGameFunction steamGetGameFunction;

    @Override
    public String namespace() {
        return "steam";
    }

    @Override
    public String name() {
        return "getCurrentGame";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("locale");
    }

    @Override
    public List<String> optionalParameterNames() {
        return List.of("locale");
    }

    @Override
    public List<String> returnKeys() {
        return steamGetGameFunction.returnKeys();
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) throws Exception {
        Optional<String> appId = gameStateService.getLastKnownGame(user)
            .filter(game -> game.getSourceType() == GameBinding.SourceType.STEAM)
            .map(DetectedGame::getSourceId);
        if (appId.isEmpty()) return Map.of();

        // Building an array element-by-element rather than always passing a fixed-size {appId,
        // locale} array: a present-but-null array slot is NOT the same as an absent one to
        // SteamGetGameFunction's own optionalArg (index < args.length is already true, so it
        // stringifies null to the literal text "null" instead of treating it as unset) — only
        // actually omitting the trailing element propagates "no locale" correctly.
        String locale = ServiceFunction.optionalArg(args, 0, null);
        Object[] delegateArgs = locale != null ? new Object[]{appId.get(), locale} : new Object[]{appId.get()};
        return steamGetGameFunction.invoke(user, delegateArgs);
    }
}
