package fr.enimaloc.catapult.chat.command.registry.igdb;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Comma-joined franchise/series names. A separate call from {@code igdb#getGame}, see {@link IgdbIdArg}. */
@Component
@RequiredArgsConstructor
public class IgdbGetFranchisesFunction implements ServiceFunction {

    private final ChatCommandServiceGateway gateway;

    @Override
    public String namespace() {
        return "igdb";
    }

    @Override
    public String name() {
        return "getFranchises";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("game");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        return gateway.igdbFranchises(IgdbIdArg.resolve(args[0])).orElse(java.util.List.of());
    }
}
