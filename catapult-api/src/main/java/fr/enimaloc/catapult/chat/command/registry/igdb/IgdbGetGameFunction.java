package fr.enimaloc.catapult.chat.command.registry.igdb;

import fr.enimaloc.catapult.chat.command.registry.DtoMapper;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import fr.enimaloc.catapult.domain.UserAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class IgdbGetGameFunction implements ServiceFunction {

    /**
     * All-{@code ""} fields when no game is found — same sentinel pattern as {@link
     * IgdbGetCurrentGameFunction#EMPTY}, deliberately NOT a bare empty map: a missing map key
     * reads as JS {@code undefined} through the sandbox's Map interop, and {@code undefined ==
     * ""} is false exactly like {@code "some value" == ""} is — making {@code
     * igdb#getGame(q).name == ""} indistinguishable between found and not-found. An all-blank
     * object makes that comparison actually work.
     */
    private static final ChatCommandServiceGateway.IgdbGame EMPTY =
        new ChatCommandServiceGateway.IgdbGame("", "", "", "", "", "", "", "");

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
    public List<String> returnKeys() {
        return DtoMapper.keys(ChatCommandServiceGateway.IgdbGame.class);
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        ChatCommandServiceGateway.IgdbGame game = gateway.igdbGame(String.valueOf(args[0])).orElse(EMPTY);
        return DtoMapper.toMap(game);
    }
}
