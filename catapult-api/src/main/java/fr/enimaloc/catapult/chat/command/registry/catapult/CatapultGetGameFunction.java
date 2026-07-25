package fr.enimaloc.catapult.chat.command.registry.catapult;

import fr.enimaloc.catapult.chat.command.registry.DtoMapper;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.GameStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The streamer's currently detected game — {@code sourceId} is the source-specific identifier
 * (e.g. the Steam appId when {@code sourceType == "STEAM"}, feeding {@code steam#getGame(id)}
 * directly), {@code sourceType} one of {@code GameBinding.SourceType}'s names ("STEAM", "XBOX",
 * "BATTLENET", "MANUAL", "MINECRAFT"), {@code sourceName} the display name.
 */
@Component
@RequiredArgsConstructor
public class CatapultGetGameFunction implements ServiceFunction {

    /** All-{@code ""} fields when no game is currently detected. */
    public record Result(String sourceId, String sourceType, String sourceName) {}

    private static final Result EMPTY = new Result("", "", "");

    private final GameStateService gameStateService;

    @Override
    public String namespace() {
        return "catapult";
    }

    @Override
    public String name() {
        return "getGame";
    }

    @Override
    public List<String> parameterNames() {
        return List.of();
    }

    @Override
    public List<String> returnKeys() {
        return DtoMapper.keys(Result.class);
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        Result result = gameStateService.getLastKnownGame(user)
            .map(CatapultGetGameFunction::toResult)
            .orElse(EMPTY);
        return DtoMapper.toMap(result);
    }

    private static Result toResult(DetectedGame game) {
        return new Result(
            game.getSourceId() != null ? game.getSourceId() : "",
            game.getSourceType() != null ? game.getSourceType().name() : "",
            game.getSourceName() != null ? game.getSourceName() : "");
    }
}
